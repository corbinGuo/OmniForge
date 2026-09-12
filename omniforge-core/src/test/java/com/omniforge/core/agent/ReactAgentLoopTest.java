package com.omniforge.core.agent;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import com.omniforge.core.gateway.ModelGateway;
import com.omniforge.core.gateway.ModelInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactAgentLoopTest {

    private ModelGateway modelGateway;
    private ChatModel chatModel;
    private ReactAgentLoop engine;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        modelGateway = mock(ModelGateway.class);
        when(modelGateway.resolveAlias(any())).thenReturn("test-model");
        when(modelGateway.routeAlias(any(), any(), any())).thenReturn("test-model");
        when(modelGateway.chatModel("test-model")).thenReturn(chatModel);
        when(modelGateway.availableModels())
                .thenReturn(List.of(new ModelInfo("test-model", "mock", "m", 1.0, 2.0, 120)));
        engine = new ReactAgentLoop(modelGateway);
    }

    @Test
    void 工具调用完整链路() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCallResponse(), textResponse("最终答案"));

        AgentRunResult result = engine.run(request(3, 60, null));

        assertEquals(StopReason.COMPLETED, result.stopReason());
        assertEquals("最终答案", result.text());
        assertEquals(2, result.rounds());
        assertEquals(1, result.steps().size());
        ToolCallRecord record = result.steps().get(0).toolCalls().get(0);
        assertEquals("echo_tool", record.toolName());
        assertEquals(ToolResult.ToolStatus.SUCCESS, record.status());
        assertEquals("echo:hi", record.result());
    }

    @Test
    void 历史消息按系统历史当前输入顺序注入() {
        // 在调用时刻快照消息列表：引擎在 call() 返回后会原地追加助手消息，
        // 事后读取 Prompt 会看到循环内部消息（思考/观察），与请求组装无关
        java.util.concurrent.atomic.AtomicReference<List<Message>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            captured.set(List.copyOf(((Prompt) invocation.getArgument(0)).getInstructions()));
            return textResponse("好");
        });
        List<Message> history = List.of(new UserMessage("历史问题"), new AssistantMessage("历史回答"));

        AgentRunResult result = engine.run(AgentRunRequest.of(
                "run-1", "s-1", "test-model", "你是助手", "新问题", List.of(), history));

        assertEquals(StopReason.COMPLETED, result.stopReason());
        List<Message> messages = captured.get();
        assertEquals(4, messages.size());
        assertEquals(MessageType.SYSTEM, messages.get(0).getMessageType());
        assertEquals(MessageType.USER, messages.get(1).getMessageType());
        assertEquals("历史问题", messages.get(1).getText());
        assertEquals(MessageType.ASSISTANT, messages.get(2).getMessageType());
        assertEquals("历史回答", messages.get(2).getText());
        assertEquals(MessageType.USER, messages.get(3).getMessageType());
        assertEquals("新问题", messages.get(3).getText());
    }

    @Test
    void 无历史时保持单条消息行为() {
        java.util.concurrent.atomic.AtomicReference<List<Message>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            captured.set(List.copyOf(((Prompt) invocation.getArgument(0)).getInstructions()));
            return textResponse("好");
        });
        engine.run(request(3, 60, null));

        List<Message> messages = captured.get();
        assertEquals(2, messages.size(), "history 为空 = 现状：system + 当前输入");
        assertEquals(MessageType.SYSTEM, messages.get(0).getMessageType());
        assertEquals(MessageType.USER, messages.get(1).getMessageType());
    }

    @Test
    void 未知工具以失败记录反馈且循环继续() {
        ChatResponse unknownTool = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("思考")
                .toolCalls(List.of(new ToolCall("tc-9", "function", "ghost", "{\"x\":1}")))
                .build())));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(unknownTool, textResponse("我换个方式回答"));

        AgentRunResult result = engine.run(request(3, 60, null));

        assertEquals(StopReason.COMPLETED, result.stopReason());
        assertEquals(1, result.steps().size());
        ToolCallRecord record = result.steps().get(0).toolCalls().get(0);
        assertEquals(ToolResult.ToolStatus.FAILED, record.status());
        assertTrue(record.result().contains("未知工具"), "未知工具应返回明确错误描述");
    }

    @Test
    void 最大轮次熔断() {
        when(chatModel.call(any(Prompt.class)))
                .thenAnswer(invocation -> toolCallResponse());

        AgentRunResult result = engine.run(request(2, 60, null));

        assertEquals(StopReason.MAX_ROUNDS, result.stopReason());
        assertEquals(2, result.rounds());
        assertEquals(2, result.steps().size());
    }

    @Test
    void 时间熔断() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(1_200);
            return textResponse("太慢了");
        });

        AgentRunResult result = engine.run(request(5, 1, null));

        assertEquals(StopReason.TIMEOUT, result.stopReason());
    }

    @Test
    void 手动打断() throws InterruptedException {
        CountDownLatch firstCallStarted = new CountDownLatch(1);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            firstCallStarted.countDown();
            Thread.sleep(300);
            return textResponse("答");
        });

        AtomicReference<AgentRunResult> ref = new AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(() -> ref.set(engine.run(request(5, 60, null))));
        assertTrue(firstCallStarted.await(5, TimeUnit.SECONDS), "模型调用应已开始");
        engine.cancel("run-1");
        runner.join(5_000);

        assertEquals(StopReason.INTERRUPTED, ref.get().stopReason());
    }

    @Test
    void 成本熔断() {
        Usage usage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return 1000;
            }

            @Override
            public Integer getCompletionTokens() {
                return 500;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        ChatResponse expensive = new ChatResponse(List.of(new Generation(new AssistantMessage("答案"))), metadata);
        when(chatModel.call(any(Prompt.class))).thenReturn(expensive);

        // 1000/1M*1.0 + 500/1M*2.0 = 0.002 USD > 0.0001
        AgentRunResult result = engine.run(request(5, 60, 0.0001));

        assertEquals(StopReason.COST_LIMIT, result.stopReason());
    }

    @Test
    void 模型异常以MODEL_ERROR返回() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("network down"));

        AgentRunResult result = engine.run(request(3, 60, null));

        assertEquals(StopReason.MODEL_ERROR, result.stopReason());
        assertTrue(result.errorMessage().contains("network down"));
    }

    @Test
    void 事件流输出工具调用步骤() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCallResponse(), textResponse("最终答案"));

        List<AgentEvent> events = engine.stream(request(3, 60, null)).collectList().block();

        assertTrue(events != null && !events.isEmpty());
        // 第一轮：文本 → 步骤开始/结束 → 第二轮：文本 → 完成
        assertTrue(events.get(0) instanceof AgentEvent.TextChunk);
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallStarted started
                && started.toolName().equals("echo_tool")));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallFinished finished
                && finished.record().result().equals("echo:hi")));
        AgentEvent.Completed completed = (AgentEvent.Completed) events.get(events.size() - 1);
        assertEquals(StopReason.COMPLETED, completed.result().stopReason());
        assertEquals("最终答案", completed.result().text());
    }

    @Test
    void 事件流失败事件() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("network down"));

        List<AgentEvent> events = engine.stream(request(3, 60, null)).collectList().block();

        assertTrue(events != null && events.get(0) instanceof AgentEvent.Failed failed
                && failed.errorMessage().contains("network down"));
    }

    private AgentRunRequest request(int maxRounds, long timeoutSeconds, Double maxCostUsd) {
        return new AgentRunRequest("run-1", "s-1", "test-model", "你是助手", "你好",
                List.of(echoTool()), maxRounds, timeoutSeconds, maxCostUsd);
    }

    private AgentRunRequest requestWithIdentity(String identityKey,
                                                java.util.List<Tool> tools) {
        return new AgentRunRequest("run-1", "s-1", "test-model", "你是助手", "你好",
                tools, 3, 60, null, java.util.List.of(), identityKey);
    }

    /** 需人工确认的工具（spec 标志 = true） */
    private static Tool confirmTool() {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("danger_tool", "危险操作", Map.of("type", "object"), true);
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return ToolResult.success("executed", 1);
            }
        };
    }

    private static ChatResponse dangerToolCallResponse() {
        AssistantMessage message = AssistantMessage.builder()
                .content("思考中")
                .toolCalls(List.of(new ToolCall("tc-1", "function", "danger_tool", "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static Tool echoTool() {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("echo_tool", "回显输入", Map.of("type", "object"), false);
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return ToolResult.success("echo:" + request.parameter("text"), 1);
            }
        };
    }

    private static ChatResponse toolCallResponse() {
        AssistantMessage message = AssistantMessage.builder()
                .content("思考中")
                .toolCalls(List.of(new ToolCall("tc-1", "function", "echo_tool", "{\"text\":\"hi\"}")))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    // ---------- HITL 确认（TOOL_CONFIRMATION A1：身份判定 + 拒绝流入模型消息） ----------

    @Test
    void os身份且通道拒绝时拒绝文本流入模型消息() {
        com.omniforge.common.spi.ToolApproval approval =
                mock(com.omniforge.common.spi.ToolApproval.class);
        when(approval.approve(any(), any())).thenReturn(false);
        ReactAgentLoop withApproval = new ReactAgentLoop(modelGateway,
                new com.fasterxml.jackson.databind.ObjectMapper(), null, List.of(), approval);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(dangerToolCallResponse(), textResponse("最终答案"));

        AgentRunResult result = withApproval.run(requestWithIdentity("os:user", List.of(confirmTool())));

        assertEquals(StopReason.COMPLETED, result.stopReason());
        assertEquals(2, result.rounds());
        assertEquals(1, result.steps().size());
        ToolCallRecord record = result.steps().get(0).toolCalls().get(0);
        assertEquals("danger_tool", record.toolName());
        assertEquals(ToolResult.ToolStatus.FAILED, record.status());
        assertEquals("ERROR: 用户拒绝执行 danger_tool", record.result());
    }

    @Test
    void im身份时不查询确认通道直接执行() {
        com.omniforge.common.spi.ToolApproval approval =
                mock(com.omniforge.common.spi.ToolApproval.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(dangerToolCallResponse(), textResponse("最终答案"));
        ReactAgentLoop withApproval = new ReactAgentLoop(modelGateway,
                new com.fasterxml.jackson.databind.ObjectMapper(), null, List.of(), approval);

        AgentRunResult result = withApproval.run(
                requestWithIdentity("im:qq:user", List.of(confirmTool())));

        assertEquals(StopReason.COMPLETED, result.stopReason());
        ToolCallRecord record = result.steps().get(0).toolCalls().get(0);
        assertEquals(ToolResult.ToolStatus.SUCCESS, record.status());
        assertEquals("executed", record.result());
        // Q4-A：im:* 身份不弹窗 → 通道零调用
        verify(approval, org.mockito.Mockito.never()).approve(any(), any());
    }

    @Test
    void 无确认通道时os身份需确认工具自动放行() {
        // approval=null（Headless 无 GUI，Q3-A 放行）
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(dangerToolCallResponse(), textResponse("最终答案"));

        AgentRunResult result = engine.run(requestWithIdentity("os:user", List.of(confirmTool())));

        assertEquals(StopReason.COMPLETED, result.stopReason());
        ToolCallRecord record = result.steps().get(0).toolCalls().get(0);
        assertEquals(ToolResult.ToolStatus.SUCCESS, record.status());
        assertEquals("executed", record.result());
    }

    @Test
    void agent运行写入审计记录() throws Exception {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCallResponse(), textResponse("最终答案"));
        com.omniforge.core.audit.AuditProperties properties =
                new com.omniforge.core.audit.AuditProperties();
        java.nio.file.Path auditDir = java.nio.file.Files.createTempDirectory("omniforge-audit-test");
        properties.setDirectory(auditDir);
        com.omniforge.core.audit.AuditLogService auditService =
                new com.omniforge.core.audit.AuditLogService(properties);
        ReactAgentLoop audited = new ReactAgentLoop(modelGateway,
                new com.fasterxml.jackson.databind.ObjectMapper(), auditService);

        AgentRunResult result = audited.run(request(3, 60, null));

        assertEquals(StopReason.COMPLETED, result.stopReason());
        List<com.omniforge.core.audit.AuditEntry> entries = auditService.query(
                java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(1));
        assertEquals(1, entries.size(), "Agent 运行应恰好产生一条审计记录");
        com.omniforge.core.audit.AuditEntry entry = entries.get(0);
        assertEquals("test-model", entry.modelAlias());
        assertEquals("agent", entry.requestType());
        assertEquals("COMPLETED", entry.stopReason());
        assertEquals(1, entry.toolCalls().size());
        assertEquals("echo_tool", entry.toolCalls().get(0).toolName());
        assertEquals(ToolResult.ToolStatus.SUCCESS.name(), entry.toolCalls().get(0).status());
    }

    @Test
    void 技能指令并入系统提示位于调用方与基线规则之间() {
        java.util.concurrent.atomic.AtomicReference<List<Message>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            captured.set(List.copyOf(((Prompt) invocation.getArgument(0)).getInstructions()));
            return textResponse("好");
        });
        SystemInstructionContributor skill = () -> "当用户要求写诗时，用十四行体（技能：sonnet）。";
        ReactAgentLoop skilled = new ReactAgentLoop(modelGateway,
                new com.fasterxml.jackson.databind.ObjectMapper(), null, List.of(skill));

        skilled.run(AgentRunRequest.of("skill-run", "s-1", "test-model", "你是助手", "新问题", List.of(), List.of()));

        Message system = captured.get().get(0);
        assertEquals(MessageType.SYSTEM, system.getMessageType());
        String text = system.getText();
        // 顺序：调用方 systemText → 技能 → 基线规则（基线恒居末）
        int caller = text.indexOf("你是助手");
        int skillAt = text.indexOf("技能：sonnet");
        int base = text.indexOf("只有 `file_read_write` 工具的实际执行结果才能决定路径是否可访问。");
        assertTrue(caller >= 0 && caller < skillAt, "调用方 systemText 应在技能之前");
        assertTrue(skillAt < base, "技能指令应位于基线规则之前");
    }

    @Test
    void 无贡献者时系统提示与既有行为一致() {
        // contributors 为空 = 基线不变（调用方 systemText + 基线规则，无技能段）
        String text = ReactAgentLoop.assembleSystemText("调用方指令", null);
        assertTrue(text.startsWith("调用方指令\n\n"));
        assertTrue(text.strip().endsWith("只有 `file_read_write` 工具的实际执行结果才能决定路径是否可访问。"));
        assertFalse(text.contains("技能"), "空贡献者不应并入任何技能指令");
        // 双空：仅基线（无前导空白段），与既有（无 systemText）行为一致
        String bare = ReactAgentLoop.assembleSystemText(null, null);
        assertEquals(ReactAgentLoop.assembleSystemText("", "  "), bare);
        assertTrue(bare.strip().endsWith("只有 `file_read_write` 工具的实际执行结果才能决定路径是否可访问。"));
    }
}
