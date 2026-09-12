package com.omniforge.core.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultContextManagerTest {

    /** 可推进时钟：TTL 验证无需真实等待 */
    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-08-28T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static DefaultContextManager manager(HistorySummarizer summarizer, int capacity,
                                                 Duration ttl, Clock clock) {
        return new DefaultContextManager(
                new ContextSettingsHolder(ContextSettings.defaults()), summarizer, capacity, ttl, clock);
    }

    /** 单条约 3000 token 的长内容（强制超预算，触发裁剪路径） */
    private static String longText(String prefix) {
        return prefix + "内容".repeat(1_500);
    }

    @Test
    void 追加后按序构建历史消息() {
        DefaultContextManager contextManager = manager(null, 256, Duration.ofHours(24), Clock.systemUTC());
        contextManager.append("s1", ContextRole.USER, "问题一");
        contextManager.append("s1", ContextRole.ASSISTANT, "回答一");
        contextManager.append("s1", ContextRole.USER, "问题二");
        contextManager.append("s1", ContextRole.ASSISTANT, "回答二");

        List<Message> history = contextManager.buildHistory("s1", "问题三", null);
        assertEquals(4, history.size());
        assertEquals(MessageType.USER, history.get(0).getMessageType());
        assertEquals("问题一", history.get(0).getText());
        assertEquals(MessageType.ASSISTANT, history.get(1).getMessageType());
        assertEquals("回答二", history.get(3).getText());
    }

    @Test
    void 关闭总开关时返回空历史() {
        ContextSettingsHolder holder = new ContextSettingsHolder(ContextSettings.defaults());
        DefaultContextManager contextManager = new DefaultContextManager(
                holder, null, 256, Duration.ofHours(24), Clock.systemUTC());
        contextManager.append("s1", ContextRole.USER, "问题");
        contextManager.append("s1", ContextRole.ASSISTANT, "回答");

        holder.update(new ContextSettings(false, 32_000, 5, TrimStrategy.SLIDING_WINDOW, null));
        assertTrue(contextManager.buildHistory("s1", "新问题", null).isEmpty(),
                "enabled=false 时完全回退单条消息现状");
    }

    @Test
    void 清空会话上下文() {
        DefaultContextManager contextManager = manager(null, 256, Duration.ofHours(24), Clock.systemUTC());
        contextManager.append("s1", ContextRole.USER, "问题");
        contextManager.append("s1", ContextRole.ASSISTANT, "回答");
        assertEquals(1, contextManager.stats("s1").turns());

        contextManager.clear("s1");
        assertTrue(contextManager.buildHistory("s1", "新问题", null).isEmpty());
        assertEquals(0, contextManager.stats("s1").turns());
    }

    @Test
    void 空闲超TTL后访问时懒淘汰() {
        MutableClock clock = new MutableClock();
        DefaultContextManager contextManager = manager(null, 256, Duration.ofHours(24), clock);
        contextManager.append("s1", ContextRole.USER, "问题");
        contextManager.append("s1", ContextRole.ASSISTANT, "回答");

        clock.advance(Duration.ofHours(25));
        assertTrue(contextManager.buildHistory("s1", "新问题", null).isEmpty(), "过期会话访问时移除");
        assertEquals(0, contextManager.stats("s1").entries());
    }

    @Test
    void 容量LRU与TTL清扫防内存泄漏() {
        MutableClock clock = new MutableClock();
        DefaultContextManager contextManager = manager(null, 2, Duration.ofHours(24), clock);
        contextManager.append("s1", ContextRole.USER, "内容一");
        clock.advance(Duration.ofHours(10));
        contextManager.append("s2", ContextRole.USER, "内容二");
        contextManager.append("s3", ContextRole.USER, "内容三");

        // s3 追加时触发容量保护（3 > 2×1.1），LRU 淘汰最久未访问的 s1
        assertEquals(0, contextManager.stats("s1").entries());
        assertEquals(1, contextManager.stats("s2").entries());
        assertEquals(1, contextManager.stats("s3").entries());

        // TTL 清扫：再前进 25h → s2/s3 空闲 25h > 24h，全部移除
        clock.advance(Duration.ofHours(25));
        contextManager.sweep();
        assertEquals(0, contextManager.stats("s2").entries());
        assertEquals(0, contextManager.stats("s3").entries());
    }

    @Test
    void 并发追加到同一会话不丢失条目() throws Exception {
        DefaultContextManager contextManager = manager(null, 256, Duration.ofHours(24), Clock.systemUTC());
        int threads = 8;
        int perThread = 50;
        var executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        List<String> errors = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        contextManager.append("s1", ContextRole.USER, "t" + threadId + "-" + i);
                        contextManager.append("s1", ContextRole.ASSISTANT, "a" + threadId + "-" + i);
                    }
                } catch (RuntimeException e) {
                    errors.add(e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        executor.shutdown();
        assertTrue(latch.await(10, TimeUnit.SECONDS), "并发追加应全部完成");
        assertTrue(errors.isEmpty(), "并发追加不应抛异常：" + errors);
        assertEquals(threads * perThread, contextManager.stats("s1").turns(), "条目不应丢失");
    }

    @Test
    void 摘要失败自动降级为滑动窗口不中断() {
        ContextSettingsHolder holder = new ContextSettingsHolder(new ContextSettings(
                true, 8_000, 1, TrimStrategy.SUMMARIZE, null));
        DefaultContextManager contextManager = new DefaultContextManager(
                holder, dropped -> {
                    throw new IllegalStateException("摘要模型不可用");
                }, 256, Duration.ofHours(24), Clock.systemUTC());
        for (int i = 1; i <= 3; i++) {
            contextManager.append("s1", ContextRole.USER, longText("问题" + i));
            contextManager.append("s1", ContextRole.ASSISTANT, longText("回答" + i));
        }

        List<Message> history = contextManager.buildHistory("s1", "新问题", null);
        // 3 对 ×约 6000 token 远超 8000 预算：摘要抛异常 → 降级纯滑动窗口，保留最后 1 对
        assertEquals(2, history.size());
        assertEquals(MessageType.USER, history.get(0).getMessageType(), "降级后无 SYSTEM 摘要条目");
        assertEquals(MessageType.ASSISTANT, history.get(1).getMessageType());
    }

    @Test
    void 裁剪后就地收敛会话历史() {
        ContextSettingsHolder holder = new ContextSettingsHolder(new ContextSettings(
                true, 8_000, 1, TrimStrategy.SUMMARIZE, null));
        DefaultContextManager contextManager = new DefaultContextManager(
                holder, dropped -> "摘要内容", 256, Duration.ofHours(24), Clock.systemUTC());
        for (int i = 1; i <= 4; i++) {
            contextManager.append("s1", ContextRole.USER, longText("问题" + i));
            contextManager.append("s1", ContextRole.ASSISTANT, longText("回答" + i));
        }
        List<Message> history = contextManager.buildHistory("s1", "新问题", null);
        // 摘要 SYSTEM 条目 + 保留的最后 1 对 = 3 条
        assertEquals(3, history.size());
        assertEquals(MessageType.SYSTEM, history.get(0).getMessageType(), "摘要条目置顶");
        // 就地收敛：会话内历史已收缩为 3 条（而非原 8 条）
        assertEquals(3, contextManager.stats("s1").entries());
    }
}
