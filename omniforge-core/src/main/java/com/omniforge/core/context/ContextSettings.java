package com.omniforge.core.context;

/**
 * 上下文设置（context.yml 持久化 + {@link ContextSettingsHolder} 热生效）。
 *
 * @param enabled             总开关，默认 true（false = 完全回退单条消息现状）
 * @param maxInputTokens      输入 token 预算，默认 32_000（用户确认：16K 改 32K）
 * @param keepRecentTurns     强制保留最近 N 轮，默认 5（用户确认：3 改 5；0 = 仅保底 1 轮）
 * @param strategy            裁剪策略，默认 SLIDING_WINDOW
 * @param summarizeModelAlias 摘要模型别名（null = 当前对话模型）
 */
public record ContextSettings(boolean enabled, int maxInputTokens, int keepRecentTurns,
                              TrimStrategy strategy, String summarizeModelAlias) {

    public static final int DEFAULT_MAX_INPUT_TOKENS = 32_000;
    public static final int DEFAULT_KEEP_RECENT_TURNS = 5;

    public ContextSettings {
        if (maxInputTokens < 1024) {
            throw new IllegalArgumentException("maxInputTokens 至少为 1024");
        }
        if (keepRecentTurns < 0) {
            throw new IllegalArgumentException("keepRecentTurns 不能为负");
        }
        strategy = strategy == null ? TrimStrategy.SLIDING_WINDOW : strategy;
    }

    /** 默认值（无配置时） */
    public static ContextSettings defaults() {
        return new ContextSettings(true, DEFAULT_MAX_INPUT_TOKENS, DEFAULT_KEEP_RECENT_TURNS,
                TrimStrategy.SLIDING_WINDOW, null);
    }

    /** 从装配层属性（omniforge.context.*，application.yml）转换 */
    public static ContextSettings from(ContextProperties properties) {
        return new ContextSettings(properties.isEnabled(), properties.getMaxInputTokens(),
                properties.getKeepRecentTurns(), properties.getStrategy(),
                properties.getSummarizeModelAlias());
    }
}
