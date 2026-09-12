package com.omniforge.core.gateway;

/**
 * 直连对话的历史消息（网关中性表示，避免 gateway 层耦合 Spring AI Message 类型）：
 * 由 {@link ContextManager} 裁剪后的历史转换而来，经网关转换为模型请求消息。
 *
 * @param role 角色（user/assistant 成对；system 为摘要条目）
 * @param text 消息内容
 */
public record GatewayHistoryMessage(GatewayHistoryRole role, String text) {

    public GatewayHistoryMessage {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
    }
}
