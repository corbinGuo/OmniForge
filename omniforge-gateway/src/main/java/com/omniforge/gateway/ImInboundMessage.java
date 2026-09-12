package com.omniforge.gateway;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 统一入站消息模型（Phase 3 Step 2）：各平台载荷经适配器归一化为此结构，
 * 与平台细节解耦（路由/幂等/白名单/处理器只面向本模型）。
 *
 * @param platform        平台标识（dingtalk/feishu/wecom/email/qq）
 * @param messageId       平台消息 ID（幂等键）
 * @param from            发送者标识（平台用户 ID / 邮箱）
 * @param text            消息正文
 * @param atMention       是否 @ 了机器人
 * @param receivedAt      接收时间
 * @param platformContext 平台回发所需上下文（如钉钉 sessionWebhook、飞书 chat_id），可为 null
 */
public record ImInboundMessage(String platform, String messageId, String from,
                               String text, boolean atMention, LocalDateTime receivedAt,
                               Map<String, Object> platformContext) {

    public ImInboundMessage(String platform, String messageId, String from,
                            String text, boolean atMention, LocalDateTime receivedAt) {
        this(platform, messageId, from, text, atMention, receivedAt, null);
    }

    public ImInboundMessage {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform must not be blank");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        text = text == null ? "" : text;
        receivedAt = receivedAt == null ? LocalDateTime.now() : receivedAt;
        platformContext = platformContext == null ? null : Map.copyOf(platformContext);
    }
}
