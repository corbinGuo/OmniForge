package com.omniforge.core.context;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文裁剪器（需求 4.2）：预算 = min(maxInputTokens, 模型 contextWindowTokens × 0.8)。
 *
 * <p>规则（优先级从高到低）：
 * <ol>
 *   <li>历史按轮对（user+assistant）从最旧开始整对删除，绝不拆对——保持消息交替，
 *       规避 DeepSeek 等 API 的用户/助手交替校验；</li>
 *   <li>强制保留最近 keepRecentTurns 轮；</li>
 *   <li>保近 N 轮与预算冲突（保留部分仍超预算）→ 例外放弃保近 N，仅保留最后 1 轮
 *       （{@link TrimResult#budgetDegraded} 标记，由调用方告警）；</li>
 *   <li>当前用户输入单独超预算时无法裁剪，原样返回；</li>
 *   <li>SUMMARIZE 策略：对被裁轮对调用摘要器生成一条 SYSTEM 条目置顶；
 *       摘要返回 null/空白 → 自动降级为纯滑动窗口。</li>
 * </ol>
 */
final class ContextTrimmer {

    /** 摘要条目前缀（置于历史最前的 SYSTEM 消息） */
    static final String SUMMARY_PREFIX = "【对话历史摘要】";
    /** 模型上下文窗口的输入预算占比（0.8 = 预留 20% 给输出） */
    static final double WINDOW_INPUT_RATIO = 0.8;

    private ContextTrimmer() {
    }

    /**
     * 裁剪结果。
     *
     * @param kept           保留条目（可能含摘要 SYSTEM 条目置顶）
     * @param summary        摘要文本（未摘要为 null）
     * @param droppedPairs   被裁掉的轮对数
     * @param budgetDegraded 是否放弃"保近 N 轮"（仅保留最后 1 轮）
     */
    record TrimResult(List<ContextEntry> kept, String summary, int droppedPairs, boolean budgetDegraded) {
    }

    /**
     * 执行裁剪。
     *
     * @param history            历史条目（顺序：user/assistant 成对）
     * @param currentUserText    当前用户输入（永远保留，计入预算）
     * @param settings           上下文设置
     * @param modelContextWindow 模型上下文窗口 token（null = 未知，仅按全局预算）
     * @param summarizer         摘要器（策略为 SUMMARIZE 时使用；可为 null = 降级）
     */
    static TrimResult trim(List<ContextEntry> history, String currentUserText,
                           ContextSettings settings, Integer modelContextWindow,
                           HistorySummarizer summarizer) {
        int budget = settings.maxInputTokens();
        if (modelContextWindow != null && modelContextWindow > 0) {
            budget = Math.min(budget, (int) (modelContextWindow * WINDOW_INPUT_RATIO));
        }
        long total = TokenEstimator.estimate(currentUserText);
        for (ContextEntry entry : history) {
            total += entry.estimatedTokens();
        }
        if (total <= budget) {
            return new TrimResult(List.copyOf(history), null, 0, false);
        }
        // 当前输入单独超预算 → 无法裁剪，原样返回（模型可能拒绝，由上层感知）
        if (TokenEstimator.estimate(currentUserText) > budget) {
            return new TrimResult(List.copyOf(history), null, 0, false);
        }

        int keptFrom = 0;
        // 阶段 1：常规裁剪，不进入强制保留区（至少保留 keepRecentTurns 轮，保底 1 轮）
        while (keptFrom + 1 < history.size() && total > budget) {
            int remainingPairs = (history.size() - keptFrom) / 2;
            if (remainingPairs <= Math.max(1, settings.keepRecentTurns())) {
                break;
            }
            total -= pairTokens(history, keptFrom);
            keptFrom += 2;
        }
        // 阶段 2：预算仍超 → 放弃保留区，仅保最后 1 对（例外降级）
        boolean degraded = false;
        while (keptFrom + 1 < history.size() && total > budget && (history.size() - keptFrom) / 2 > 1) {
            total -= pairTokens(history, keptFrom);
            keptFrom += 2;
            degraded = true;
        }

        int droppedPairs = keptFrom / 2;
        String summary = null;
        if (settings.strategy() == TrimStrategy.SUMMARIZE && droppedPairs > 0 && summarizer != null) {
            summary = summarizer.summarize(List.copyOf(history.subList(0, keptFrom)));
            // 空白摘要视为不可用 → 降级为纯滑动窗口
            if (summary != null && summary.isBlank()) {
                summary = null;
            }
        }

        List<ContextEntry> kept = new ArrayList<>(history.size() - keptFrom + 1);
        if (summary != null) {
            String summaryText = SUMMARY_PREFIX + summary;
            kept.add(new ContextEntry(ContextRole.SYSTEM, summaryText,
                    TokenEstimator.estimate(summaryText)));
        }
        kept.addAll(history.subList(keptFrom, history.size()));
        return new TrimResult(List.copyOf(kept), summary, droppedPairs, degraded);
    }

    private static long pairTokens(List<ContextEntry> history, int pairStart) {
        return history.get(pairStart).estimatedTokens()
                + history.get(pairStart + 1).estimatedTokens();
    }
}
