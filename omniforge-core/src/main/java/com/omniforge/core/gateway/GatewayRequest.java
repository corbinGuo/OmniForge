package com.omniforge.core.gateway;

/**
 * 模型网关统一请求。
 *
 * @param alias       模型别名（models.yml 中定义）；为 null 时走路由（默认/智能）
 * @param systemText  系统提示词（可为 null）
 * @param userText    用户输入（必填）
 * @param temperature 采样温度（可为 null，使用模型默认值）
 * @param maxTokens   输出 token 上限（可为 null，使用模型默认值）
 * @param history     多轮对话历史（ContextManager 裁剪后注入；空 = 单条消息，保持既有行为）
 * @param identityKey 调用方身份键（GUI="os:用户名"，IM="im:平台:发送者"；null=匿名/默认策略；
 *                    路由策略按它匹配，Batch2 权限模型）
 */
public record GatewayRequest(String alias, String systemText, String userText,
                             Double temperature, Integer maxTokens,
                             java.util.List<GatewayHistoryMessage> history, String identityKey) {

    /** 便捷构造：不携带历史（兼容既有调用方） */
    public GatewayRequest(String alias, String systemText, String userText,
                          Double temperature, Integer maxTokens) {
        this(alias, systemText, userText, temperature, maxTokens, java.util.List.of(), null);
    }

    /** 便捷构造：携带历史、无身份（兼容既有调用方） */
    public GatewayRequest(String alias, String systemText, String userText,
                          Double temperature, Integer maxTokens,
                          java.util.List<GatewayHistoryMessage> history) {
        this(alias, systemText, userText, temperature, maxTokens, history, null);
    }

    public GatewayRequest {
        if (userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("userText must not be blank");
        }
        history = history == null ? java.util.List.of() : java.util.List.copyOf(history);
    }

    /** 仅带用户输入的便捷构造（使用默认模型/匿名身份） */
    public static GatewayRequest of(String userText) {
        return new GatewayRequest(null, null, userText, null, null, java.util.List.of(), null);
    }
}
