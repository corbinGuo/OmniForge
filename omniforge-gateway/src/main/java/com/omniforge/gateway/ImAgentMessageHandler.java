package com.omniforge.gateway;

import com.omniforge.common.spi.Tool;
import com.omniforge.core.agent.AgentEngine;
import com.omniforge.core.agent.AgentRunRequest;
import com.omniforge.core.agent.StopReason;
import com.omniforge.core.agent.ToolRegistry;
import com.omniforge.core.context.ContextManager;
import com.omniforge.core.context.ContextRole;
import com.omniforge.core.debate.DebateEngine;
import com.omniforge.core.debate.DebateRequest;
import com.omniforge.core.gateway.ModelGateway;
import com.omniforge.core.gateway.ModelInfo;
import com.omniforge.core.persistence.service.ChatSessionService;
import com.omniforge.core.persistence.service.LicenseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * IM → Agent 触发处理器（Phase 3 Step 4，MessageHandler SPI 实现）：
 *
 * <pre>
 * 文本以「辩论：」开头 → 多模型辩论（参与模型 = 全部可用模型，2~10 个），回复最终轮各方观点摘要；
 * 其他 → 单模型 Agent 执行（全量工具），回复结果文本。
 * 回复统一附加 AI 生成标识脚注（合规底线），经平台 ImReplySender 回发。
 * </pre>
 *
 * <p>多轮上下文（Phase 4 ContextManager）：会话键 = im:平台:会话标识
 * （platformContext 的 chatId/sessionWebhook，缺失时退回发送者 from）；
 * 回复回发成功后追加本轮 user/assistant 到会话上下文（失败不追加，重试时不重复记录），
 * 下次请求携带裁剪后的历史。contextManager 为 null 时完全保持单条消息行为。</p>
 *
 * <p>各平台适配器不感知 Agent 逻辑——全部经 MessageHandler SPI 汇聚于此。</p>
 */
public class ImAgentMessageHandler implements MessageHandler {

    /** AI 生成标识脚注（需求 9.2：所有 IM 输出必须包含） */
    public static final String AI_FOOTNOTE = "\n\n——本条回复由 AI 自动生成。";

    private static final Logger log = LoggerFactory.getLogger(ImAgentMessageHandler.class);

    private final AgentEngine agentEngine;
    private final ModelGateway modelGateway;
    private final ToolRegistry toolRegistry;
    private final DebateEngine debateEngine; // 可为 null（辩论引擎未装配时降级）
    private final LicenseService licenseService; // 可为 null
    private final Map<String, ImReplySender> replySenders;
    private final ContextManager contextManager; // 可为 null（未装配时单条消息行为）
    private final ChatSessionService chatSessionService; // 可为 null（未装配时仅上下文记忆，不落库）

    /** IM 会话键 → 持久化会话 ID（历史侧栏可见的 Session 表） */
    private final Map<String, String> persistedSessionIds = new ConcurrentHashMap<>();

    /** 兼容构造（不落库：仅上下文记忆） */
    public ImAgentMessageHandler(AgentEngine agentEngine, ModelGateway modelGateway,
                                 ToolRegistry toolRegistry, DebateEngine debateEngine,
                                 LicenseService licenseService, List<ImReplySender> senders,
                                 ContextManager contextManager) {
        this(agentEngine, modelGateway, toolRegistry, debateEngine, licenseService, senders,
                contextManager, null);
    }

    public ImAgentMessageHandler(AgentEngine agentEngine, ModelGateway modelGateway,
                                 ToolRegistry toolRegistry, DebateEngine debateEngine,
                                 LicenseService licenseService, List<ImReplySender> senders,
                                 ContextManager contextManager, ChatSessionService chatSessionService) {
        this.agentEngine = agentEngine;
        this.modelGateway = modelGateway;
        this.toolRegistry = toolRegistry;
        this.debateEngine = debateEngine;
        this.licenseService = licenseService;
        this.contextManager = contextManager;
        this.chatSessionService = chatSessionService;
        this.replySenders = senders.stream()
                .collect(Collectors.toMap(ImReplySender::platform, Function.identity()));
    }

    @Override
    public void handle(ImInboundMessage message) throws Exception {
        String sessionId = sessionKey(message);
        boolean debate = message.text().stripLeading().startsWith("辩论：");
        AgentOutcome outcome;
        if (debate) {
            outcome = new AgentOutcome(
                    runDebate(message.text().stripLeading().substring("辩论：".length()).trim()), null);
        } else {
            outcome = runAgent(message.text(), sessionId, imIdentity(message));
        }
        String replyText = outcome.text();
        sendReply(message, replyText + AI_FOOTNOTE);
        // 回发成功后追加会话上下文（失败不追加：重试时不会重复记录同一轮）
        if (contextManager != null && !debate && !replyText.isBlank()) {
            contextManager.append(sessionId, ContextRole.USER, message.text());
            contextManager.append(sessionId, ContextRole.ASSISTANT, replyText);
        }
        // 回发成功后落库到会话列表（历史侧栏可见；失败仅告警不阻塞 IM 流程）
        if (chatSessionService != null) {
            try {
                persistTurn(sessionId, message.text(), replyText, outcome.modelAlias());
            } catch (Exception e) {
                log.warn("IM 会话落库失败（不影响回复）：{}", e.getMessage());
            }
        }
    }

    /**
     * 单模型 Agent 执行；首次请求 alias=null 走身份路由（Batch2），
     * 路由/默认模型调用失败（MODEL_ERROR）时自动依次尝试其他可用模型，
     * 成功则回复正文并附切换提示；全部失败返回错误说明。
     */
    private AgentOutcome runAgent(String text, String sessionId, String identityKey) {
        String alias = modelGateway.resolveAlias(null);
        var tools = toolRegistry.all().stream().toList();
        var result = agentEngine.run(requestOf(null, sessionId, text, tools, identityKey));
        if (result.stopReason() != StopReason.MODEL_ERROR) {
            return new AgentOutcome(
                    result.text().isBlank() ? "（无回复内容）" : result.text(), alias);
        }
        // 降级：尝试其他可用模型
        List<String> tried = new java.util.ArrayList<>();
        tried.add(alias);
        for (ModelInfo other : modelGateway.availableModels()) {
            if (other.alias().equals(alias)) {
                continue;
            }
            tried.add(other.alias());
            var fallback = agentEngine.run(requestOf(other.alias(), sessionId, text, tools, identityKey));
            if (fallback.stopReason() == StopReason.MODEL_ERROR) {
                continue;
            }
            String body = fallback.text().isBlank() ? "（无回复内容）" : fallback.text();
            return new AgentOutcome(body
                    + "\n\n⚠ 模型 " + alias + " 调用失败（" + result.errorMessage()
                    + "），已自动切换至 " + other.alias() + " 回复。", other.alias());
        }
        return new AgentOutcome("处理失败：" + result.errorMessage()
                + "\n（已尝试全部可用模型：" + String.join("、", tried) + "，均调用失败，请检查模型账户与密钥）",
                alias);
    }

    /** IM 身份键（Batch2 路由规则匹配）：im:平台:发送者 */
    private static String imIdentity(ImInboundMessage message) {
        String from = message.from();
        return "im:" + message.platform() + ":" + (from == null || from.isBlank() ? "default" : from);
    }

    private AgentRunRequest requestOf(String alias, String sessionId, String text, List<Tool> tools,
                                      String identityKey) {
        List<Message> history = contextManager != null
                ? contextManager.buildHistory(sessionId, text, contextWindow(alias))
                : List.of();
        return AgentRunRequest.of(
                UUID.randomUUID().toString(), sessionId, alias, null, text, tools, history, identityKey);
    }

    private String runDebate(String topic) {
        if (topic.isBlank()) {
            return "辩论主题不能为空（格式：辩论：主题内容）";
        }
        int maxModels = licenseService != null ? licenseService.maxDebateModels()
                : LicenseService.PRO_MAX_DEBATE_MODELS;
        List<String> aliases = modelGateway.availableModels().stream()
                .map(model -> model.alias()).limit(maxModels).toList();
        if (aliases.size() < 2) {
            return "可用模型不足 2 个，无法发起辩论（当前：" + aliases.size() + "）";
        }
        if (debateEngine == null) {
            return "辩论引擎未装配，暂不支持辩论";
        }
        DebateRequest request = DebateRequest.of(
                UUID.randomUUID().toString(), topic, null, aliases);
        var result = debateEngine.stream(request)
                .collectList()
                .map(events -> events.stream()
                        .filter(e -> e instanceof com.omniforge.core.debate.DebateEvent.Completed)
                        .map(e -> (com.omniforge.core.debate.DebateEvent.Completed) e)
                        .findFirst().orElse(null))
                .block();
        if (result == null) {
            return "辩论执行失败";
        }
        StringBuilder summary = new StringBuilder("辩论结束（" + result.result().stopReason() + "）：\n");
        var rounds = result.result().rounds();
        if (rounds.isEmpty()) {
            return summary.toString();
        }
        rounds.get(rounds.size() - 1).modelTexts().forEach((alias, text) ->
                summary.append("◆ ").append(alias).append("：")
                        .append(text.length() > 300 ? text.substring(0, 300) + "…" : text)
                        .append('\n'));
        return summary.toString().trim();
    }

    /** IM 轮次落库：会话首轮自动创建（标题取首条消息前 30 字），后续轮追加到同一会话 */
    private void persistTurn(String imSessionKey, String userText, String assistantText, String modelAlias) {
        String persistedId = persistedSessionIds.get(imSessionKey);
        if (persistedId == null) {
            persistedId = chatSessionService.appendTurn(null, userText, assistantText, modelAlias);
            persistedSessionIds.put(imSessionKey, persistedId);
        } else {
            chatSessionService.appendTurn(persistedId, userText, assistantText, modelAlias);
        }
    }

    /** Agent 执行结果：文本 + 实际使用的模型别名（降级切换后为最终模型；辩论为 null） */
    private record AgentOutcome(String text, String modelAlias) {
    }

    private void sendReply(ImInboundMessage message, String replyText) throws Exception {
        ImReplySender sender = replySenders.get(message.platform());
        if (sender == null) {
            log.warn("平台 [{}] 无回复通道（回发被忽略）", message.platform());
            return;
        }
        sender.send(message, replyText);
    }

    /** 会话键：im:平台:会话标识（chatId → sessionWebhook → from → default），各平台各会话上下文隔离 */
    private static String sessionKey(ImInboundMessage message) {
        Map<String, Object> context = message.platformContext();
        Object chatId = null;
        if (context != null) {
            chatId = context.get("chatId");
            if (chatId == null) {
                chatId = context.get("sessionWebhook");
            }
        }
        if (chatId == null) {
            chatId = message.from();
        }
        return "im:" + message.platform() + ":" + (chatId == null ? "default" : chatId);
    }

    /** 模型上下文窗口 token（alias 为 null=走路由未定，返回 null 仅按全局预算；其余按别名） */
    private Integer contextWindow(String alias) {
        if (alias == null) {
            return null;
        }
        return modelGateway.availableModels().stream()
                .filter(model -> alias.equals(model.alias()))
                .map(ModelInfo::contextWindowTokens)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
