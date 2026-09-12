package com.omniforge.core.gateway;

/**
 * 直连对话历史消息角色（与 {@link GatewayHistoryMessage} 配套）。
 * SYSTEM 用于上下文摘要条目（被裁剪历史压缩后的【对话历史摘要】）。
 */
public enum GatewayHistoryRole {

    SYSTEM,
    USER,
    ASSISTANT
}
