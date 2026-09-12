package com.omniforge.core.context;

/**
 * 会话上下文统计信息（状态栏/日志展示用）。
 *
 * @param turns            完整轮数（user+assistant 对）
 * @param entries          历史条目总数（含摘要条目）
 * @param estimatedTokens  全部条目估算 token 合计
 */
public record ContextStats(int turns, int entries, long estimatedTokens) {

    /** 空会话统计 */
    public static ContextStats empty() {
        return new ContextStats(0, 0, 0);
    }
}
