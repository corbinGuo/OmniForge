package com.omniforge.common.retry;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 通用指数退避组件：连续失败时重试间隔按 2 的幂递增，成功即复位为基础间隔；
 * 上限封顶 + 随机抖动。
 *
 * <p>设计要点：
 * <ul>
 *   <li>第 n 次连续失败后等待 base×2^n（首次失败即翻倍），封顶 maxMillis；
 *       避免服务端不可达时以固定短间隔反复重试（部分邮件服务商对
 *       频繁失败认证有封号策略）；</li>
 *   <li>±jitterFactor 随机抖动（默认 0.2）：多实例部署时错开重试时刻，
 *       避免"惊群"同时打满服务端；</li>
 *   <li>成功即复位（连续失败计数器清零），恢复后回到基础间隔；</li>
 *   <li>线程安全（轮询循环单线程使用，但 MCP 等场景可能在多线程调用，
 *       统一加锁）；</li>
 *   <li>零外部依赖，可复用：邮件轮询（omniforge-gateway）、
 *       MCP 服务器重连（omniforge-tools）、将来的出站长连接适配器共用。</li>
 * </ul>
 */
public class ExponentialBackoff {

    private final long baseMillis;
    private final long maxMillis;
    private final double jitterFactor;
    private int consecutiveFailures;

    public ExponentialBackoff(long baseMillis, long maxMillis) {
        this(baseMillis, maxMillis, 0.2);
    }

    /**
     * @param baseMillis   基础间隔（毫秒，>0）
     * @param maxMillis    封顶间隔（毫秒，≥baseMillis）
     * @param jitterFactor 抖动系数 [0, 1)：实际间隔 = 原始 × (1 ± jitterFactor)；
     *                     0 = 无抖动（测试确定性）
     */
    public ExponentialBackoff(long baseMillis, long maxMillis, double jitterFactor) {
        if (baseMillis <= 0) {
            throw new IllegalArgumentException("baseMillis must be > 0");
        }
        if (maxMillis < baseMillis) {
            throw new IllegalArgumentException("maxMillis must be >= baseMillis");
        }
        if (jitterFactor < 0 || jitterFactor >= 1) {
            throw new IllegalArgumentException("jitterFactor must be in [0, 1)");
        }
        this.baseMillis = baseMillis;
        this.maxMillis = maxMillis;
        this.jitterFactor = jitterFactor;
    }

    /** 记录一次失败：退避间隔翻倍（封顶 + 抖动），返回本次等待毫秒数 */
    public synchronized long onFailure() {
        consecutiveFailures++;
        // 首次失败即翻倍（基础间隔 30s → 首次重试 60s），随后每失败一次再翻倍，封顶 maxMillis
        long raw = baseMillis;
        for (int i = 0; i < consecutiveFailures && raw < maxMillis; i++) {
            raw = Math.min(raw * 2, maxMillis);
        }
        return jitter(raw);
    }

    /** 记录一次成功：复位为基础间隔，返回本次等待毫秒数 */
    public synchronized long onSuccess() {
        consecutiveFailures = 0;
        return baseMillis;
    }

    /** 当前连续失败次数（0 = 正常） */
    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    private long jitter(long raw) {
        if (jitterFactor == 0) {
            return raw;
        }
        double factor = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitterFactor;
        return Math.min((long) (raw * factor), maxMillis);
    }
}
