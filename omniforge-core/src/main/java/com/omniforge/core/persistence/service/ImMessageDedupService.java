package com.omniforge.core.persistence.service;

import com.omniforge.core.persistence.entity.ImMessageDedup;
import com.omniforge.core.persistence.repository.ImMessageDedupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * IM 消息幂等去重服务（v5.1 决议 #5，需求 4.6 配套）。
 *
 * <p>流程：
 * <ol>
 *   <li>收到 IM 消息 → {@link #check}：不存在 → 插入 processing 并返回 NEW（入任务队列）；</li>
 *   <li>processing → 返回 IN_PROGRESS（防重入，回复"执行中"）；</li>
 *   <li>completed → 返回 ALREADY_PROCESSED（永久拒绝，回复"已处理"）；</li>
 *   <li>failed → 重新置为 processing 并返回 NEW（允许重试）；</li>
 *   <li>任务完成 → {@link #markCompleted}；失败 → {@link #markFailed}。</li>
 * </ol>
 */
public class ImMessageDedupService {

    private final ImMessageDedupRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    public ImMessageDedupService(ImMessageDedupRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /** 幂等判定结果 */
    public enum Decision {
        /** 新消息：应入队处理 */
        NEW,
        /** 正在处理中：直接回复"执行中" */
        IN_PROGRESS,
        /** 已处理完成：永久拒绝（回复"已处理"） */
        ALREADY_PROCESSED
    }

    /**
     * 幂等检查：以原生 upsert（INSERT ... ON CONFLICT DO NOTHING）原子抢占，
     * 唯一索引保证并发下只有一人插入成功（不依赖 JPA 方言的 DDL 唯一约束能力）。
     */
    @Transactional
    public Decision check(String platform, String messageId) {
        ensureUniqueIndex();
        int inserted = ((Number) entityManager.createNativeQuery(
                        "INSERT INTO im_message_dedup (id, message_id, platform, status) "
                                + "VALUES (:id, :messageId, :platform, :status) "
                                + "ON CONFLICT(message_id, platform) DO NOTHING")
                .setParameter("id", UUID.randomUUID().toString())
                .setParameter("messageId", messageId)
                .setParameter("platform", platform)
                .setParameter("status", ImMessageDedup.STATUS_PROCESSING)
                .executeUpdate()).intValue();
        if (inserted > 0) {
            return Decision.NEW;
        }
        ImMessageDedup existing = repository.findByMessageIdAndPlatform(messageId, platform)
                .orElseThrow(() -> new IllegalStateException(
                        "去重记录缺失: " + platform + "/" + messageId));
        return switch (existing.getStatus()) {
            case ImMessageDedup.STATUS_COMPLETED -> Decision.ALREADY_PROCESSED;
            case ImMessageDedup.STATUS_FAILED -> {
                existing.setStatus(ImMessageDedup.STATUS_PROCESSING); // 允许重试，重新入队
                yield Decision.NEW;
            }
            default -> Decision.IN_PROGRESS;
        };
    }

    /**
     * 幂等建唯一索引（Hibernate 社区版 SQLiteDialect 不渲染 @Table(uniqueConstraints)，
     * 必须显式创建；IF NOT EXISTS 保证幂等，可安全重复调用）。
     */
    private void ensureUniqueIndex() {
        entityManager.createNativeQuery(
                        "CREATE UNIQUE INDEX IF NOT EXISTS uk_im_message_dedup_message_platform "
                                + "ON im_message_dedup (message_id, platform)")
                .executeUpdate();
        entityManager.createNativeQuery(
                        "CREATE UNIQUE INDEX IF NOT EXISTS uk_session_models_session_model "
                                + "ON session_models (session_id, model_name)")
                .executeUpdate();
    }

    /** 标记处理完成（写入会话关联） */
    @Transactional
    public void markCompleted(String platform, String messageId, String sessionId) {
        repository.findByMessageIdAndPlatform(messageId, platform).ifPresent(entity -> {
            entity.setStatus(ImMessageDedup.STATUS_COMPLETED);
            entity.setSessionId(sessionId);
            entity.setProcessedAt(LocalDateTime.now());
        });
    }

    /** 标记处理失败（允许后续重试） */
    @Transactional
    public void markFailed(String platform, String messageId) {
        repository.findByMessageIdAndPlatform(messageId, platform).ifPresent(entity -> {
            entity.setStatus(ImMessageDedup.STATUS_FAILED);
            entity.setProcessedAt(LocalDateTime.now());
        });
    }
}
