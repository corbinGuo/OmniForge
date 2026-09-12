package com.omniforge.core.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link ContextManager} 默认实现（进程内存态）。
 *
 * <p>存储与淘汰（防内存泄漏，双路径）：
 * <ul>
 *   <li>会话容量上限（默认 256）：追加时超限即 LRU 淘汰最久未访问会话；</li>
 *   <li>空闲 TTL（默认 24h）：访问时懒淘汰（过期即移除）+
 *       定时清扫（每 30 分钟，{@link SmartLifecycle} 启动的守护线程）；</li>
 * </ul>
 *
 * <p>裁剪采用"就地收敛"：{@link #trimmedHistory} 裁剪发生时同步收缩会话内历史
 * （被裁轮对移除、摘要条目持久保留），内存占用随裁剪递减，
 * 摘要无需每轮重复生成。</p>
 */
public class DefaultContextManager implements ContextManager, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextManager.class);
    /** 定时清扫周期 */
    static final Duration SWEEP_INTERVAL = Duration.ofMinutes(30);
    /** 容量保护触发余量（追加时超过 capacity×1.1 再收敛，避免频繁全表排序） */
    static final double CAPACITY_HEADROOM = 1.1;

    private final ConcurrentHashMap<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final ContextSettingsHolder settingsHolder;
    private final HistorySummarizer summarizer; // 可为 null（未装配模型网关时）
    private final int capacity;
    private final Duration ttl;
    private final Clock clock;
    private volatile ScheduledExecutorService sweeper;
    private volatile boolean running;

    public DefaultContextManager(ContextSettingsHolder settingsHolder, HistorySummarizer summarizer,
                                 int capacity, Duration ttl) {
        this(settingsHolder, summarizer, capacity, ttl, Clock.systemUTC());
    }

    /** 测试可见：注入时钟，TTL 验证无需真实等待 */
    DefaultContextManager(ContextSettingsHolder settingsHolder, HistorySummarizer summarizer,
                          int capacity, Duration ttl, Clock clock) {
        this.settingsHolder = Objects.requireNonNull(settingsHolder, "settingsHolder");
        this.summarizer = summarizer;
        this.capacity = capacity > 0 ? capacity : 256;
        this.ttl = ttl != null && !ttl.isNegative() ? ttl : Duration.ofHours(24);
        this.clock = Objects.requireNonNull(clock, "clock");
        // 摘要失败绝不中断对话：包装为返回 null（裁剪器自动降级纯滑动窗口）
        if (this.summarizer != null) {
            log.info("上下文管理已启用：容量 {} 会话 / TTL {} / 摘要 {}",
                    this.capacity, this.ttl, "开启");
        }
    }

    @Override
    public void append(String sessionId, ContextRole role, String content) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (content == null || content.isBlank()) {
            return;
        }
        SessionContext context = sessions.computeIfAbsent(sessionId, key -> new SessionContext());
        synchronized (context) {
            context.touch(clock.millis());
            context.entries.add(new ContextEntry(role, content, TokenEstimator.estimate(content)));
        }
        if (sessions.size() > (int) (capacity * CAPACITY_HEADROOM)) {
            sweepCapacity();
        }
    }

    @Override
    public List<ContextEntry> trimmedHistory(String sessionId, String userText, Integer modelContextWindow) {
        Objects.requireNonNull(sessionId, "sessionId");
        ContextSettings settings = settingsHolder.current();
        if (!settings.enabled()) {
            return List.of();
        }
        SessionContext context = sessions.get(sessionId);
        if (context == null) {
            return List.of();
        }
        long now = clock.millis();
        if (now - context.lastAccessMillis() > ttl.toMillis()) {
            sessions.remove(sessionId, context);
            return List.of();
        }
        context.touch(now);
        synchronized (context) {
            List<ContextEntry> snapshot = List.copyOf(context.entries);
            ContextTrimmer.TrimResult result = ContextTrimmer.trim(
                    snapshot, userText, settings, modelContextWindow, safeSummarizer());
            if (result.droppedPairs() > 0) {
                // 就地收敛：裁剪结果（含摘要条目）替换会话历史，内存随裁剪递减
                context.entries.clear();
                context.entries.addAll(result.kept());
                if (result.budgetDegraded()) {
                    log.warn("上下文预算与保近 {} 轮冲突，已降级仅保留最后 1 轮：sessionId={}",
                            settings.keepRecentTurns(), sessionId);
                } else {
                    log.info("上下文裁剪：sessionId={}，裁掉 {} 轮{}", sessionId,
                            result.droppedPairs(),
                            result.summary() == null ? "" : "（已摘要保留）");
                }
            }
            return result.kept();
        }
    }

    @Override
    public void clear(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        sessions.remove(sessionId);
        log.info("上下文已清空：sessionId={}", sessionId);
    }

    @Override
    public ContextStats stats(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        SessionContext context = sessions.get(sessionId);
        if (context == null) {
            return ContextStats.empty();
        }
        if (clock.millis() - context.lastAccessMillis() > ttl.toMillis()) {
            sessions.remove(sessionId, context);
            return ContextStats.empty();
        }
        synchronized (context) {
            long tokens = 0;
            int turns = 0;
            for (ContextEntry entry : context.entries) {
                tokens += entry.estimatedTokens();
                if (entry.role() != ContextRole.SYSTEM) {
                    turns++;
                }
            }
            return new ContextStats(turns / 2, context.entries.size(), tokens);
        }
    }

    // ---------- 淘汰（防内存泄漏） ----------

    /** 摘要器包装：摘要失败/异常 → null（裁剪器自动降级纯滑动窗口，绝不中断对话） */
    private HistorySummarizer safeSummarizer() {
        if (summarizer == null) {
            return null;
        }
        return dropped -> {
            try {
                return summarizer.summarize(dropped);
            } catch (RuntimeException e) {
                log.warn("上下文摘要失败，已降级为纯滑动窗口：{}", e.getMessage());
                return null;
            }
        };
    }

    /** 定时清扫：TTL 过期移除 + 容量 LRU 收敛 */
    void sweep() {
        long now = clock.millis();
        sessions.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMillis() > ttl.toMillis());
        sweepCapacity();
        if (log.isDebugEnabled()) {
            log.debug("上下文清扫完成：当前 {} 个会话", sessions.size());
        }
    }

    /** 容量 LRU 收敛：超限时按最后访问时间移除最旧会话 */
    private void sweepCapacity() {
        int excess = sessions.size() - capacity;
        if (excess <= 0) {
            return;
        }
        List<Map.Entry<String, SessionContext>> lru = sessions.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessMillis()))
                .limit(excess)
                .toList();
        lru.forEach(entry -> sessions.remove(entry.getKey()));
        if (!lru.isEmpty()) {
            log.warn("上下文会话超容量，已 LRU 淘汰 {} 个会话", lru.size());
        }
    }

    // ---------- SmartLifecycle（定时清扫线程） ----------

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "omniforge-context-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(this::sweep, SWEEP_INTERVAL.toMinutes(),
                SWEEP_INTERVAL.toMinutes(), TimeUnit.MINUTES);
    }

    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService current = sweeper;
        sweeper = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 会话上下文：条目列表 + 最后访问时间（访问/追加时 touch） */
    private static final class SessionContext {

        private final List<ContextEntry> entries = new ArrayList<>();
        private volatile long lastAccess;

        void touch(long nowMillis) {
            lastAccess = nowMillis;
        }

        long lastAccessMillis() {
            return lastAccess;
        }
    }
}
