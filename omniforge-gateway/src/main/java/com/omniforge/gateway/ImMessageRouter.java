package com.omniforge.gateway;

import com.omniforge.common.security.InputSanitizer;
import com.omniforge.core.persistence.entity.ImMessage;
import com.omniforge.core.persistence.repository.ImMessageRepository;
import com.omniforge.core.persistence.service.ImMessageDedupService;
import com.omniforge.core.scheduler.OmniForgeExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * IM 消息路由（Phase 3 Step 2 网关骨架）：
 *
 * <pre>
 * 入站消息 → 幂等去重（ImMessageDedupService，(message_id, platform) 复合唯一）
 *         → 白名单权限校验（拒绝即终态，永久去重）
 *         → 消息日志落库（ImMessage）
 *         → 异步任务队列（虚拟线程）→ MessageHandler（Step 3+ 接入 Agent 触发）
 * </pre>
 */
public class ImMessageRouter {

    /** 路由结果 */
    public enum RouterResult {
        /** 已受理并入队 */
        ACCEPTED,
        /** 正在处理中（防重入） */
        IN_PROGRESS,
        /** 已处理完成（永久拒绝） */
        ALREADY_PROCESSED,
        /** 白名单拒绝（终态） */
        REJECTED
    }

    private static final Logger log = LoggerFactory.getLogger(ImMessageRouter.class);

    private final ImMessageDedupService dedupService;
    private final ImWhitelist whitelist;
    private final ImMessageRepository messageLog;
    private final MessageHandler handler; // 可为 null（Step 2 无处理器）
    private final Executor executor;

    public ImMessageRouter(ImMessageDedupService dedupService, ImWhitelist whitelist,
                           ImMessageRepository messageLog, MessageHandler handler) {
        this(dedupService, whitelist, messageLog, handler,
                OmniForgeExecutors.newVirtualThreadPerTaskExecutor("omniforge-im"));
    }

    ImMessageRouter(ImMessageDedupService dedupService, ImWhitelist whitelist,
                    ImMessageRepository messageLog, MessageHandler handler,
                    Executor executor) {
        this.dedupService = Objects.requireNonNull(dedupService, "dedupService");
        this.whitelist = Objects.requireNonNull(whitelist, "whitelist");
        this.messageLog = Objects.requireNonNull(messageLog, "messageLog");
        this.handler = handler;
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** 路由一条入站消息 */
    public RouterResult route(ImInboundMessage message) {
        Objects.requireNonNull(message, "message");

        // 0. 输入清洗防注入（Phase 4 安全底线；对外输入统一入口）
        final ImInboundMessage sanitized = new ImInboundMessage(
                message.platform(), message.messageId(), message.from(),
                InputSanitizer.sanitize(message.text()), message.atMention(),
                message.receivedAt(), message.platformContext());

        // 1. 幂等去重（processing 防重入 / completed 永久拒绝 / failed 允许重试）
        ImMessageDedupService.Decision decision =
                dedupService.check(sanitized.platform(), sanitized.messageId());
        if (decision == ImMessageDedupService.Decision.IN_PROGRESS) {
            return RouterResult.IN_PROGRESS;
        }
        if (decision == ImMessageDedupService.Decision.ALREADY_PROCESSED) {
            return RouterResult.ALREADY_PROCESSED;
        }

        // 2. 白名单权限校验（拒绝 = 终态，标记完成避免重复骚扰）
        if (!whitelist.allows(sanitized.from())) {
            dedupService.markCompleted(sanitized.platform(), sanitized.messageId(), null);
            log.warn("IM 消息被白名单拒绝：platform={}, from={}", sanitized.platform(), sanitized.from());
            return RouterResult.REJECTED;
        }

        // 3. 消息日志落库
        messageLog.save(new ImMessage(sanitized.platform(), message.messageId(), message.from(),
                sanitized.text(), sanitized.atMention(), sanitized.receivedAt()));

        // 4. 异步任务队列（虚拟线程）；处理器异常 → 标记失败允许重试
        executor.execute(() -> {
            try {
                if (handler != null) {
                    handler.handle(sanitized);
                }
                dedupService.markCompleted(sanitized.platform(), sanitized.messageId(), null);
            } catch (Exception e) {
                log.error("IM 消息处理失败：platform={}, messageId={}", message.platform(),
                        message.messageId(), e);
                dedupService.markFailed(sanitized.platform(), sanitized.messageId());
            }
        });
        return RouterResult.ACCEPTED;
    }
}
