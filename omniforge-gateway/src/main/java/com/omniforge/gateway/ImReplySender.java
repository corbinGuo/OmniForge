package com.omniforge.gateway;

/**
 * IM 回复发送 SPI（Phase 3 Step 4）：各平台回复通道（钉钉 webhook 加签 /
 * 飞书卡片 / 企微应用消息 / 邮件 SMTP）。AI 标识脚注由调用方（Agent 处理器）统一附加。
 */
public interface ImReplySender {

    /** 平台标识（与 ImInboundMessage.platform 一致） */
    String platform();

    /**
     * 向消息来源回复文本。
     *
     * @param original  原始入站消息（含 platformContext：回发地址/会话信息）
     * @param replyText 回复正文（已含 AI 标识脚注）
     */
    void send(ImInboundMessage original, String replyText) throws Exception;
}
