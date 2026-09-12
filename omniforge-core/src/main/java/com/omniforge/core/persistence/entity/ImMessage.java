package com.omniforge.core.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * IM 收件消息日志（Phase 3 Step 2 统一消息模型）。
 *
 * <p>与 {@code ImMessageDedup} 分工：Dedup 负责幂等（(message_id, platform) 复合唯一），
 * 本表为消息正文归档（无唯一约束，仅日志）。</p>
 */
@Entity
@Table(name = "im_message")
public class ImMessage {

    @Id
    @Column(length = 36)
    private String id;

    /** 平台：dingtalk / feishu / wecom / email / qq */
    private String platform;

    /** 平台消息 ID（幂等键的一部分） */
    @Column(name = "message_id")
    private String messageId;

    /** 发送者标识（平台用户 ID / 邮箱地址） */
    private String sender;

    @Column(columnDefinition = "TEXT")
    private String text;

    /** 是否 @ 了机器人 */
    @Column(name = "at_mention")
    private boolean atMention;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    /** 处理后关联的会话 ID（可为空） */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    protected ImMessage() {
        this.id = UUID.randomUUID().toString();
    }

    public ImMessage(String platform, String messageId, String sender, String text,
                     boolean atMention, LocalDateTime receivedAt) {
        this();
        this.platform = platform;
        this.messageId = messageId;
        this.sender = sender;
        this.text = text;
        this.atMention = atMention;
        this.receivedAt = receivedAt;
    }

    public String getId() {
        return id;
    }

    public String getPlatform() {
        return platform;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSender() {
        return sender;
    }

    public String getText() {
        return text;
    }

    public boolean isAtMention() {
        return atMention;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
