package com.omniforge.core.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.common.exception.ModelGatewayException;
import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolApproval;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.core.audit.AuditEntry;
import com.omniforge.core.audit.AuditLogService;
import com.omniforge.core.gateway.ModelGateway;
import com.omniforge.core.gateway.ModelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * ReAct 循环引擎（思考-行动-观察，需求 4.2）。
 *
 * <p>循环结构（每轮）：
 * <ol>
 *   <li>检查 手动打断 → 时间熔断；</li>
 *   <li>携带历史消息与工具定义调用模型（ToolCallingChatModel）；</li>
 *   <li>累计 token 与估算成本，检查 成本熔断；</li>
 *   <li>无工具调用 → 正常完成；否则执行工具（观察），将结果追加为 ToolResponseMessage 进入下一轮；</li>
 *   <li>轮次耗尽 → 最大轮次熔断。</li>
 * </ol>
 *
 * <p>实现选择说明：基于 Spring AI 核心的 ToolCallingChatModel 自研循环，
 * 而非直接依赖 Spring AI Alibaba ReactAgent——两者能力等价（ReAct + 工具调用），
 * 但本实现将停止逻辑（多重熔断）织入循环内，且 API 面更稳定；
 * SAA 内置编排模式（Sequential/Parallel/Routing/LoopAgent）在 Phase 2 辩论引擎中
 * 作为 {@link AgentEngine} 的替换实现接入。</p>
 */
public class ReactAgentLoop implements AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentLoop.class);

    /**
     * 基线系统提示（文件路径纪律，2026-09）：普通单模型 Agent 路径默认无调用方 system 文本
     * （UI 直连与 IM 网关均传 null），模型仅凭工具 description 可能绕过 file_read_write
     * 直接臆测运行环境（如"Linux 无法访问 D 盘"）。此处强制先调工具、以工具结果为准。
     * 有调用方 systemText 时拼接在其后（规则位于 prompt 末尾）。
     */
    private static final String BASE_SYSTEM_PROMPT = """
            你是运行在用户本机上的助手，拥有本地工具（file_read_write、shell_executor 等），
            可访问用户授权范围内的真实文件系统，文件是否可访问取决于工具的实际执行结果。

            ## 文件路径处理规则（必须严格遵守）

            当用户请求列出、读取、写入文件或目录时，你**必须**先调用 `file_read_write` 工具，再根据工具返回的结果进行回复。

            正确流程：
            1. 识别用户的文件操作意图
            2. 调用 `file_read_write` 工具，传入用户指定的路径
            3. 根据工具返回的结果（成功/失败），给出对应的回复

            严禁行为：
            - 在未调用工具的情况下，直接回复“无法访问”或“环境不支持”
            - 根据路径格式（如 D:\\）推断操作系统类型，并据此拒绝操作
            - 使用搜索或其他工具的结果来代替 `file_read_write` 工具的执行

            记住：只有 `file_read_write` 工具的实际执行结果才能决定路径是否可访问。
            """;

    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, AtomicBoolean> interrupts = new ConcurrentHashMap<>();

    /** 审计日志服务（可空：未装配时审计关闭；写入失败不影响调用链） */
    private final AuditLogService auditService;

    /** 系统提示增量贡献者（P1-3 Agent Skills；空 = 行为与既有完全一致） */
    private final List<SystemInstructionContributor> instructionContributors;

    /** 工具执行人工确认通道（HITL，可为 null：无通道自动放行 + WARN，Q3-A） */
    private final ToolApproval toolApproval;

    public ReactAgentLoop(ModelGateway modelGateway) {
        this(modelGateway, new ObjectMapper(), null, List.of(), null);
    }

    ReactAgentLoop(ModelGateway modelGateway, ObjectMapper objectMapper) {
        this(modelGateway, objectMapper, null, List.of(), null);
    }

    ReactAgentLoop(ModelGateway modelGateway, ObjectMapper objectMapper, AuditLogService auditService) {
        this(modelGateway, objectMapper, auditService, List.of(), null);
    }

    ReactAgentLoop(ModelGateway modelGateway, ObjectMapper objectMapper, AuditLogService auditService,
                   List<SystemInstructionContributor> instructionContributors) {
        this(modelGateway, objectMapper, auditService, instructionContributors, null);
    }

    /** 完整构造：确认通道经装配层注入（AgentAutoConfiguration ObjectProvider；null 零影响） */
    ReactAgentLoop(ModelGateway modelGateway, ObjectMapper objectMapper, AuditLogService auditService,
                   List<SystemInstructionContributor> instructionContributors,
                   ToolApproval toolApproval) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.auditService = auditService;
        this.instructionContributors = instructionContributors == null
                ? List.of() : List.copyOf(instructionContributors);
        this.toolApproval = toolApproval;
    }

    @Override
    public AgentRunResult run(AgentRunRequest request) {
        Objects.requireNonNull(request, "request");
        interrupts.remove(request.runId());
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + request.timeoutSeconds() * 1_000_000_000L;

        // 身份+文本感知路由（Batch2）：显式 alias 直通；null 时按身份策略/复杂度选模型
        String alias = modelGateway.routeAlias(request.alias(), request.identityKey(), request.userText());
        ChatModel chatModel = modelGateway.chatModel(alias);
        // 注：Spring AI 1.1.2 已移除 ToolCallingChatModel 接口，
        // 工具调用能力由 ToolCallingChatOptions 承载；不支持的模型由 SDK 返回错误
        ModelInfo modelInfo = resolveModelInfo(alias);

        List<ToolCallback> callbacks = request.tools().stream()
                .map(tool -> new OmniForgeToolCallback(tool, objectMapper, toolApproval,
                        interactiveApproval(request)))
                .collect(Collectors.toCollection(ArrayList::new));
        Map<String, Tool> toolsByName = new HashMap<>();
        for (Tool tool : request.tools()) {
            toolsByName.putIfAbsent(tool.spec().name(), tool);
        }

        List<Message> messages = buildMessages(request,
                2 + request.history().size() + request.maxRounds() * 2);
        logMessages(request.runId(), messages);

        List<AgentStep> steps = new ArrayList<>();
        long inputTokens = 0;
        long outputTokens = 0;
        double costUsd = 0;

        for (int round = 1; round <= request.maxRounds(); round++) {
            if (isInterrupted(request.runId())) {
                return result(request, lastText(steps), steps, round, inputTokens, outputTokens,
                        StopReason.INTERRUPTED, startNanos, null);
            }
            if (System.nanoTime() > deadlineNanos) {
                return result(request, lastText(steps), steps, round, inputTokens, outputTokens,
                        StopReason.TIMEOUT, startNanos, null);
            }

            ChatResponse response;
            try {
                // Spring AI 1.1：工具经 ToolCallingChatOptions 传入；
                // 关闭内部执行循环（默认开启会吞掉中间步骤），由本引擎手动驱动，
                // 以获得逐步观测点（TOOL_CALL_LOG 记录与多重熔断）
                ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                        .toolCallbacks(callbacks.toArray(new ToolCallback[0]))
                        .internalToolExecutionEnabled(false)
                        .build();
                response = chatModel.call(new Prompt(messages, options));
            } catch (RuntimeException e) {
                log.error("Agent 模型调用失败：runId={}, alias={}", request.runId(), alias, e);
                return result(request, lastText(steps), steps, round, inputTokens, outputTokens,
                        StopReason.MODEL_ERROR, startNanos, e.getMessage());
            }
            AssistantMessage assistant = extractAssistant(response);
            messages.add(assistant);

            if (isInterrupted(request.runId())) {
                return result(request, assistant.getText(), steps, round, inputTokens, outputTokens,
                        StopReason.INTERRUPTED, startNanos, null);
            }
            if (System.nanoTime() > deadlineNanos) {
                return result(request, assistant.getText(), steps, round, inputTokens, outputTokens,
                        StopReason.TIMEOUT, startNanos, null);
            }

            // Token 与成本累计（成本熔断数据来源，需求 4.2）
            var usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
            if (usage != null) {
                int in = nz(usage.getPromptTokens());
                int out = nz(usage.getCompletionTokens());
                inputTokens += in;
                outputTokens += out;
                costUsd += in / 1_000_000.0 * modelInfo.inputPricePer1m()
                        + out / 1_000_000.0 * modelInfo.outputPricePer1m();
            }
            if (request.maxCostUsd() != null && costUsd > request.maxCostUsd()) {
                log.warn("Agent 成本熔断：runId={}, 累计成本 ${}", request.runId(), String.format("%.6f", costUsd));
                return result(request, assistant.getText(), steps, round, inputTokens, outputTokens,
                        StopReason.COST_LIMIT, startNanos, null);
            }

            List<ToolCall> toolCalls = assistant.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                return result(request, assistant.getText(), steps, round, inputTokens, outputTokens,
                        StopReason.COMPLETED, startNanos, null);
            }

            // 行动+观察：执行工具并将结果反馈模型
            List<ToolCallRecord> records = new ArrayList<>(toolCalls.size());
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>(toolCalls.size());
            for (ToolCall call : toolCalls) {
                long toolStart = System.nanoTime();
                Tool tool = toolsByName.get(call.name());
                String resultText;
                ToolResult.ToolStatus status;
                if (tool == null) {
                    status = ToolResult.ToolStatus.FAILED;
                    resultText = "ERROR: 未知工具: " + call.name();
                } else {
                    Map<String, Object> params = parseArguments(call.arguments());
                    if (requiresApproval(request, tool, params)) {
                        status = ToolResult.ToolStatus.FAILED;
                        resultText = "ERROR: 用户拒绝执行 " + call.name();
                    } else {
                        try {
                            ToolResult toolResult = tool.execute(
                                    new ToolRequest(params, request.sessionId(), Map.of()));
                            status = toolResult.status();
                            resultText = switch (toolResult.status()) {
                                case SUCCESS -> toolResult.output();
                                case FAILED, TIMEOUT -> "ERROR: " + toolResult.error();
                            };
                        } catch (RuntimeException e) {
                            status = ToolResult.ToolStatus.FAILED;
                            resultText = "ERROR: " + e.getMessage();
                        }
                    }
                }
                records.add(new ToolCallRecord(call.name(), call.arguments(), resultText, status,
                        (System.nanoTime() - toolStart) / 1_000_000));
                toolResponses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), resultText));
            }
            steps.add(new AgentStep(round, assistant.getText(), records));
            messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
        }

        log.warn("Agent 达到最大轮次熔断：runId={}, maxRounds={}", request.runId(), request.maxRounds());
        return result(request, lastText(steps), steps, request.maxRounds(), inputTokens, outputTokens,
                StopReason.MAX_ROUNDS, startNanos, null);
    }

    @Override
    public Flux<AgentEvent> stream(AgentRunRequest request) {
        Objects.requireNonNull(request, "request");
        // 生成器为阻塞循环：强制在弹性线程执行，避免 UI 线程订阅时冻结界面
        return Flux.<AgentEvent>create(sink -> runStreaming(request, sink))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 事件流式执行（与 run() 同构的循环，差异仅在以事件输出）。
     * 注：当前每轮文本以整块 TextChunk 输出；Agent 循环内逐 token 流式
     * （流式响应中 toolCalls 的合并处理）为后续增量。
     */
    private void runStreaming(AgentRunRequest request, FluxSink<AgentEvent> sink) {
        interrupts.remove(request.runId());
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + request.timeoutSeconds() * 1_000_000_000L;

        // 身份+文本感知路由（Batch2）：显式 alias 直通；null 时按身份策略/复杂度选模型
        String alias = modelGateway.routeAlias(request.alias(), request.identityKey(), request.userText());
        ChatModel chatModel = modelGateway.chatModel(alias);
        ModelInfo modelInfo = resolveModelInfo(alias);

        List<ToolCallback> callbacks = request.tools().stream()
                .map(tool -> new OmniForgeToolCallback(tool, objectMapper, toolApproval,
                        interactiveApproval(request)))
                .collect(Collectors.toCollection(ArrayList::new));
        Map<String, Tool> toolsByName = new HashMap<>();
        for (Tool tool : request.tools()) {
            toolsByName.putIfAbsent(tool.spec().name(), tool);
        }

        List<Message> messages = buildMessages(request, 2 + request.history().size());
        logMessages(request.runId(), messages);

        List<AgentStep> steps = new ArrayList<>();
        long inputTokens = 0;
        long outputTokens = 0;
        double costUsd = 0;

        try {
            for (int round = 1; round <= request.maxRounds(); round++) {
                if (sink.isCancelled()) {
                    return;
                }
                if (isInterrupted(request.runId())) {
                    sink.next(new AgentEvent.Completed(result(request, lastText(steps), steps, round,
                            inputTokens, outputTokens, StopReason.INTERRUPTED, startNanos, null)));
                    return;
                }
                if (System.nanoTime() > deadlineNanos) {
                    sink.next(new AgentEvent.Completed(result(request, lastText(steps), steps, round,
                            inputTokens, outputTokens, StopReason.TIMEOUT, startNanos, null)));
                    return;
                }

                ChatResponse response;
                try {
                    ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                            .toolCallbacks(callbacks.toArray(new ToolCallback[0]))
                            .internalToolExecutionEnabled(false)
                            .build();
                    response = chatModel.call(new Prompt(messages, options));
                } catch (RuntimeException e) {
                    sink.next(new AgentEvent.Failed(e.getMessage()));
                    return;
                }
                AssistantMessage assistant = extractAssistant(response);
                messages.add(assistant);
                sink.next(new AgentEvent.TextChunk(assistant.getText()));

                if (isInterrupted(request.runId())) {
                    sink.next(new AgentEvent.Completed(result(request, assistant.getText(), steps, round,
                            inputTokens, outputTokens, StopReason.INTERRUPTED, startNanos, null)));
                    return;
                }
                if (System.nanoTime() > deadlineNanos) {
                    sink.next(new AgentEvent.Completed(result(request, assistant.getText(), steps, round,
                            inputTokens, outputTokens, StopReason.TIMEOUT, startNanos, null)));
                    return;
                }

                var usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
                if (usage != null) {
                    int in = nz(usage.getPromptTokens());
                    int out = nz(usage.getCompletionTokens());
                    inputTokens += in;
                    outputTokens += out;
                    costUsd += in / 1_000_000.0 * modelInfo.inputPricePer1m()
                            + out / 1_000_000.0 * modelInfo.outputPricePer1m();
                }
                if (request.maxCostUsd() != null && costUsd > request.maxCostUsd()) {
                    sink.next(new AgentEvent.Completed(result(request, assistant.getText(), steps, round,
                            inputTokens, outputTokens, StopReason.COST_LIMIT, startNanos, null)));
                    return;
                }

                List<ToolCall> toolCalls = assistant.getToolCalls();
                if (toolCalls == null || toolCalls.isEmpty()) {
                    sink.next(new AgentEvent.Completed(result(request, assistant.getText(), steps, round,
                            inputTokens, outputTokens, StopReason.COMPLETED, startNanos, null)));
                    return;
                }

                List<ToolCallRecord> records = new ArrayList<>(toolCalls.size());
                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>(toolCalls.size());
                for (ToolCall call : toolCalls) {
                    sink.next(new AgentEvent.ToolCallStarted(call.name(), call.arguments()));
                    long toolStart = System.nanoTime();
                    Tool tool = toolsByName.get(call.name());
                    String resultText;
                    ToolResult.ToolStatus status;
                    if (tool == null) {
                        status = ToolResult.ToolStatus.FAILED;
                        resultText = "ERROR: 未知工具: " + call.name();
                    } else {
                        Map<String, Object> params = parseArguments(call.arguments());
                        if (requiresApproval(request, tool, params)) {
                            status = ToolResult.ToolStatus.FAILED;
                            resultText = "ERROR: 用户拒绝执行 " + call.name();
                        } else {
                            try {
                                ToolResult toolResult = tool.execute(
                                        new ToolRequest(params, request.sessionId(), Map.of()));
                                status = toolResult.status();
                                resultText = switch (toolResult.status()) {
                                    case SUCCESS -> toolResult.output();
                                    case FAILED, TIMEOUT -> "ERROR: " + toolResult.error();
                                };
                            } catch (RuntimeException e) {
                                status = ToolResult.ToolStatus.FAILED;
                                resultText = "ERROR: " + e.getMessage();
                            }
                        }
                    }
                    ToolCallRecord record = new ToolCallRecord(call.name(), call.arguments(), resultText, status,
                            (System.nanoTime() - toolStart) / 1_000_000);
                    records.add(record);
                    sink.next(new AgentEvent.ToolCallFinished(record));
                    toolResponses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), resultText));
                }
                steps.add(new AgentStep(round, assistant.getText(), records));
                messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
            }
            sink.next(new AgentEvent.Completed(result(request, lastText(steps), steps, request.maxRounds(),
                    inputTokens, outputTokens, StopReason.MAX_ROUNDS, startNanos, null)));
        } catch (RuntimeException e) {
            sink.next(new AgentEvent.Failed(e.getMessage()));
        } finally {
            sink.complete();
        }
    }

    @Override
    public void cancel(String runId) {
        interrupts.computeIfAbsent(runId, k -> new AtomicBoolean()).set(true);
    }

    private boolean isInterrupted(String runId) {
        AtomicBoolean flag = interrupts.get(runId);
        return flag != null && flag.get();
    }

    private ModelInfo resolveModelInfo(String alias) {
        return modelGateway.availableModels().stream()
                .filter(m -> alias.equals(m.alias()))
                .findFirst()
                .orElseThrow(() -> new ModelGatewayException("未找到模型信息: " + alias));
    }

    private AssistantMessage extractAssistant(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ModelGatewayException("模型返回了空响应");
        }
        return response.getResult().getOutput();
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of("_raw", arguments);
        }
    }

    /** HITL 交互身份判定（Q4-A）：仅桌面本地 {@code os:*} 身份启用弹窗确认；im 身份/无身份 → 放行 */
    private static boolean interactiveApproval(AgentRunRequest request) {
        String identity = request.identityKey();
        return identity != null && identity.startsWith("os:");
    }

    /**
     * 执行前人工确认（HITL，Q1-Q4）：
     * 需确认（工具/操作级标志）+ 交互身份 + 有确认通道 → 征求用户；拒绝 → 返回 true（调用方反馈拒绝文本）。
     * 无通道自动放行 + WARN（Q3-A）。
     */
    private boolean requiresApproval(AgentRunRequest request, Tool tool, Map<String, Object> params) {
        if (!interactiveApproval(request)) {
            return false;
        }
        if (!tool.requiresConfirmation(params)) {
            return false;
        }
        if (toolApproval == null) {
            log.warn("工具 {} 需人工确认但无确认通道（Headless/无 GUI），自动放行（Q3-A）",
                    tool.spec().name());
            return false;
        }
        return !toolApproval.approve(tool.spec(), params);
    }

    private static String lastText(List<AgentStep> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i).assistantText() != null && !steps.get(i).assistantText().isBlank()) {
                return steps.get(i).assistantText();
            }
        }
        return "";
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private AgentRunResult result(AgentRunRequest request, String text, List<AgentStep> steps,
                                  int rounds, long inputTokens, long outputTokens,
                                  StopReason stopReason, long startNanos, String errorMessage) {
        AgentRunResult result = new AgentRunResult(request.runId(), text == null ? "" : text,
                List.copyOf(steps), rounds, inputTokens, outputTokens, stopReason,
                (System.nanoTime() - startNanos) / 1_000_000, errorMessage);
        recordAudit(request, result);
        return result;
    }

    /**
     * 审计旁路（批次 4-1）：Agent 调用链结构化落盘。
     * run()/stream() 的全部退出路径都经 {@link #result} 咽喉点，此处只记一次；
     * 任何失败仅告警，绝不影响主调用链。
     */
    private void recordAudit(AgentRunRequest request, AgentRunResult result) {
        if (auditService == null) {
            return;
        }
        try {
            String alias = modelGateway.resolveAlias(request.alias());
            ModelInfo modelInfo = resolveModelInfo(alias);
            double costUsd = result.inputTokens() / 1_000_000.0 * modelInfo.inputPricePer1m()
                    + result.outputTokens() / 1_000_000.0 * modelInfo.outputPricePer1m();
            List<AuditEntry.AuditToolCall> toolCalls = result.steps().stream()
                    .flatMap(step -> step.toolCalls().stream())
                    .map(record -> new AuditEntry.AuditToolCall(record.toolName(), record.arguments(),
                            truncate(record.result(), 500), record.status().name(), record.durationMs()))
                    .toList();
            auditService.record(new AuditEntry(
                    java.time.Instant.now(),
                    System.getProperty("user.name", "local"),
                    request.sessionId(),
                    request.runId(),
                    alias,
                    "agent",
                    truncate(request.userText(), 2000),
                    result.stopReason().name(),
                    result.durationMs(),
                    result.inputTokens(),
                    result.outputTokens(),
                    costUsd,
                    toolCalls));
        } catch (RuntimeException e) {
            log.debug("审计记录失败（已跳过）：{}", e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /**
     * 组装首轮输入消息：基线 system（有调用方 systemText 时拼接其后，规则位于末尾）→ 历史 → 当前输入。
     * run()/runStreaming() 共用，避免两处消息组装发散。
     */
    private List<Message> buildMessages(AgentRunRequest request, int capacity) {
        List<Message> messages = new ArrayList<>(capacity);
        messages.add(new SystemMessage(assembleSystemText(request.systemText(), collectSkillText())));
        messages.addAll(request.history());
        messages.add(new UserMessage(request.userText()));
        return messages;
    }

    /** 汇总全部贡献者指令（P1-3 Agent Skills）：逐调用实时读取，启用/停用即时生效 */
    private String collectSkillText() {
        if (instructionContributors.isEmpty()) {
            return null;
        }
        String joined = instructionContributors.stream()
                .map(SystemInstructionContributor::extraSystemText)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"));
        return joined.isBlank() ? null : joined;
    }

    /**
     * 组装系统提示：调用方 systemText → 技能指令（contributors）→ 基线规则。
     * 基线规则恒居末（文件路径纪律为 prompt 末尾最强约束）；二者皆空时仅基线。
     */
    static String assembleSystemText(String callerSystemText, String skillText) {
        StringBuilder system = new StringBuilder();
        if (callerSystemText != null && !callerSystemText.isBlank()) {
            system.append(callerSystemText).append("\n\n");
        }
        if (skillText != null && !skillText.isBlank()) {
            system.append(skillText).append("\n\n");
        }
        system.append(BASE_SYSTEM_PROMPT);
        return system.toString();
    }

    /** P0 诊断：模型调用前打印消息构成，确认历史消息（ContextManager）已注入 */
    private static void logMessages(String runId, List<Message> messages) {
        long systemCount = messages.stream().filter(m -> m instanceof SystemMessage).count();
        long historyCount = Math.max(0, messages.size() - systemCount - 1); // 末条为当前输入
        log.info("Agent 输入消息 N={}（system={}，history={}，当前输入=1）：runId={}",
                messages.size(), systemCount, historyCount, runId);
    }
}
