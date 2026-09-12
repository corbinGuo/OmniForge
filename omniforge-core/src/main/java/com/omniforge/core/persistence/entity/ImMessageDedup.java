package com.omniforge.core.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * IM 消息幂等去重（v5.1 决议 #5，需求 4.6 配套）。
 *
 * <p>去重逻辑：
 * <ul>
 *   <li>不存在 → 插入 processing 并放入任务队列；</li>
 *   <li>processing → 返回"执行中"（防重入）；</li>
 *   <li>completed → 永久拒绝（返回"已处理"）；</li>
 *   <li>failed → 允许重试（重新置为 processing 入队）。</li>
 * </ul>
 */
@Entity
@Table(name = "im_message_dedup",
        uniqueConstraints = @UniqueConstraint(name = "uk_im_message_dedup_message_platform",
                columnNames = {"message_id", "platform"}))
public class ImMessageDedup {

    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    /** IM 平台："dingtalk" | "feishu" | "wecom" | "email" */
    public static final String PLATFORM_DINGTALK = "dingtalk";
    public static final String PLATFORM_FEISHU = "feishu";
    public static final String PLATFORM_WECOM = "wecom";
    public static final String PLATFORM_EMAIL = "email";

    @Id
    @Column(length = 36)
    private String id;

    /** IM 平台消息 ID（唯一索引） */
    @Column(name = "message_id", nullable = false)
    private String messageId;

    private String platform;

    private String status;

    /** 关联的会话 ID（可为空：未入队或处理失败时无会话） */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    private LocalDateTime processedAt;

    protected ImMessageDedup() {
        this.id = UUID.randomUUID().toString();
    }

    public ImMessageDedup(String messageId, String platform) {
        this();
        this.messageId = messageId;
        this.platform = platform;
        this.status = STATUS_PROCESSING;
    }

    public String getId() {
        return id;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }
}
