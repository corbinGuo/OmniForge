package com.omniforge.gateway;

import com.omniforge.core.persistence.repository.ImMessageRepository;
import com.omniforge.core.persistence.service.ImMessageDedupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息路由骨架测试（幂等 → 白名单 → 日志 → 异步队列）。
 */
class ImMessageRouterTest {

    private ImMessageDedupService dedupService;
    private ImMessageRepository messageLog;
    private ImMessageRouter router;

    @BeforeEach
    void setUp() {
        dedupService = mock(ImMessageDedupService.class);
        messageLog = mock(ImMessageRepository.class);
    }

    private ImMessageRouter router(ImWhitelist whitelist, MessageHandler handler) {
        // 直接执行器：任务同步完成，测试确定性强（避免虚拟线程调度竞态）
        return new ImMessageRouter(dedupService, whitelist, messageLog, handler, Runnable::run);
    }

    @Test
    void 新消息受理并异步处理完成() throws InterruptedException {
        when(dedupService.check("dingtalk", "m1")).thenReturn(ImMessageDedupService.Decision.NEW);
        CountDownLatch handled = new CountDownLatch(1);
        ImMessageRouter router = router(new ImWhitelist(false, List.of()),
                message -> handled.countDown());

        ImMessageRouter.RouterResult result = router.route(
                new ImInboundMessage("dingtalk", "m1", "user-1", "你好", false, LocalDateTime.now()));

        assertEquals(ImMessageRouter.RouterResult.ACCEPTED, result);
        assertTrue(handled.await(5, TimeUnit.SECONDS), "处理器应在异步队列中被调用");
        verify(messageLog).save(any());
        verify(dedupService).markCompleted("dingtalk", "m1", null);
    }

    @Test
    void 重复到达防重入与永久拒绝() {
        when(dedupService.check("dingtalk", "m1")).thenReturn(ImMessageDedupService.Decision.IN_PROGRESS);
        assertEquals(ImMessageRouter.RouterResult.IN_PROGRESS,
                router(new ImWhitelist(false, List.of()), null)
                        .route(new ImInboundMessage("dingtalk", "m1", "u", "hi", false, null)));

        when(dedupService.check("dingtalk", "m1")).thenReturn(ImMessageDedupService.Decision.ALREADY_PROCESSED);
        assertEquals(ImMessageRouter.RouterResult.ALREADY_PROCESSED,
                router(new ImWhitelist(false, List.of()), null)
                        .route(new ImInboundMessage("dingtalk", "m1", "u", "hi", false, null)));
    }

    @Test
    void 白名单拒绝为终态且不触发处理器() throws InterruptedException {
        when(dedupService.check("dingtalk", "m1")).thenReturn(ImMessageDedupService.Decision.NEW);
        CountDownLatch handled = new CountDownLatch(1);
        ImMessageRouter router = router(new ImWhitelist(true, List.of("admin_*")),
                message -> handled.countDown());

        ImMessageRouter.RouterResult result = router.route(
                new ImInboundMessage("dingtalk", "m1", "stranger", "hi", false, LocalDateTime.now()));

        assertEquals(ImMessageRouter.RouterResult.REJECTED, result);
        verify(dedupService).markCompleted("dingtalk", "m1", null); // 拒绝即终态
        verify(messageLog, never()).save(any());
        assertTrue(!handled.await(300, TimeUnit.MILLISECONDS), "被拒绝的消息不应触发处理器");
    }

    @Test
    void 处理器异常标记失败允许重试() throws InterruptedException {
        when(dedupService.check("dingtalk", "m1")).thenReturn(ImMessageDedupService.Decision.NEW);
        CountDownLatch failed = new CountDownLatch(1);
        ImMessageRouter router = router(new ImWhitelist(false, List.of()), message -> {
            throw new IllegalStateException("boom");
        });

        router.route(new ImInboundMessage("dingtalk", "m1", "u", "hi", false, LocalDateTime.now()));

        Thread.sleep(300); // 等待异步任务
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(dedupService).markFailed(captor.capture(), captor.capture());
        assertEquals("dingtalk", captor.getAllValues().get(0));
        assertEquals("m1", captor.getAllValues().get(1));
        failed.countDown();
    }
}
