package com.omniforge.core.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上下文管理装配级属性（application.yml 的 omniforge.context.*）。
 * 运行时默认值经 {@link ContextSettings#from} 进入持有器，
 * 配置中心保存的 context.yml 热生效后覆盖。
 */
@ConfigurationProperties("omniforge.context")
public class ContextProperties {

    /** 总开关（false = 完全回退单条消息现状） */
    private boolean enabled = true;

    /** 输入 token 预算 */
    private int maxInputTokens = ContextSettings.DEFAULT_MAX_INPUT_TOKENS;

    /** 强制保留最近 N 轮 */
    private int keepRecentTurns = ContextSettings.DEFAULT_KEEP_RECENT_TURNS;

    /** 裁剪策略 */
    private TrimStrategy strategy = TrimStrategy.SLIDING_WINDOW;

    /** 摘要模型别名（null = 当前对话模型） */
    private String summarizeModelAlias;

    /** 会话容量上限（防 IM 高频场景内存无界增长） */
    private int capacity = 256;

    /** 会话空闲淘汰时间（小时） */
    private long ttlHours = 24;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxInputTokens() {
        return maxInputTokens;
    }

    public void setMaxInputTokens(int maxInputTokens) {
        this.maxInputTokens = maxInputTokens;
    }

    public int getKeepRecentTurns() {
        return keepRecentTurns;
    }

    public void setKeepRecentTurns(int keepRecentTurns) {
        this.keepRecentTurns = keepRecentTurns;
    }

    public TrimStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(TrimStrategy strategy) {
        this.strategy = strategy;
    }

    public String getSummarizeModelAlias() {
        return summarizeModelAlias;
    }

    public void setSummarizeModelAlias(String summarizeModelAlias) {
        this.summarizeModelAlias = summarizeModelAlias;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public long getTtlHours() {
        return ttlHours;
    }

    public void setTtlHours(long ttlHours) {
        this.ttlHours = ttlHours;
    }
}
