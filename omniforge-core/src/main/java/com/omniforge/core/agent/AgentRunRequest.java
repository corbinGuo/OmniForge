package com.omniforge.core.agent;

import com.omniforge.common.spi.Tool;

import java.util.List;

/**
 * Agent 运行请求。
 *
 * @param runId          运行唯一标识（用于取消/审计/日志追踪）
 * @param sessionId      所属会话 ID（可为 null，系统级调用）
 * @param alias          模型别名（null 时走路由：身份策略/智能路由，Batch2）
 * @param systemText     系统提示词（可为 null）
 * @param userText       用户输入（必填）
 * @param tools          本次运行可用的工具集（空集合 = 纯对话）
 * @param maxRounds      最大轮次熔断（需求 4.2）
 * @param timeoutSeconds 整体运行时间熔断（秒）
 * @param maxCostUsd     成本熔断预算（美元，null = 不启用）
 * @param history        多轮对话历史（ContextManager 裁剪后注入，system → user 之前；
 *                       空 = 单条消息，保持既有行为）
 * @param identityKey    调用方身份键（GUI="os:用户名"，IM="im:平台:发送者"；null=匿名/默认策略）
 */
public record AgentRunRequest(String runId, String sessionId, String alias, String systemText,
                              String userText, List<Tool> tools, int maxRounds,
                              long timeoutSeconds, Double maxCostUsd,
                              java.util.List<org.springframework.ai.chat.messages.Message> history,
                              String identityKey) {

    /** 便捷构造：携带历史、无身份（兼容既有调用方） */
    public AgentRunRequest(String runId, String sessionId, String alias, String systemText,
                           String userText, List<Tool> tools, int maxRounds,
                           long timeoutSeconds, Double maxCostUsd,
                           java.util.List<org.springframework.ai.chat.messages.Message> history) {
        this(runId, sessionId, alias, systemText, userText, tools, maxRounds, timeoutSeconds, maxCostUsd,
                history, null);
    }

    /** 便捷构造：不携带历史（兼容既有调用方） */
    public AgentRunRequest(String runId, String sessionId, String alias, String systemText,
                           String userText, List<Tool> tools, int maxRounds,
                           long timeoutSeconds, Double maxCostUsd) {
        this(runId, sessionId, alias, systemText, userText, tools, maxRounds, timeoutSeconds, maxCostUsd,
                List.of(), null);
    }

    public AgentRunRequest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("userText must not be blank");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        history = history == null ? List.of() : List.copyOf(history);
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be >= 1");
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be >= 1");
        }
    }

    /** 便捷构造：默认 maxRounds=10、timeoutSeconds=300、不启用成本熔断 */
    public static AgentRunRequest of(String runId, String sessionId, String alias,
                                     String systemText, String userText, List<Tool> tools) {
        return new AgentRunRequest(runId, sessionId, alias, systemText, userText, tools, 10, 300, null);
    }

    /** 便捷构造：携带对话历史，默认 maxRounds=10、timeoutSeconds=300、不启用成本熔断 */
    public static AgentRunRequest of(String runId, String sessionId, String alias,
                                     String systemText, String userText, List<Tool> tools,
                                     java.util.List<org.springframework.ai.chat.messages.Message> history) {
        return new AgentRunRequest(runId, sessionId, alias, systemText, userText, tools, 10, 300, null,
                history, null);
    }

    /** 便捷构造：携带历史与身份键（Batch2 路由，identityKey 形如 im:平台:发送者） */
    public static AgentRunRequest of(String runId, String sessionId, String alias,
                                     String systemText, String userText, List<Tool> tools,
                                     java.util.List<org.springframework.ai.chat.messages.Message> history,
                                     String identityKey) {
        return new AgentRunRequest(runId, sessionId, alias, systemText, userText, tools, 10, 300, null,
                history, identityKey);
    }
}
