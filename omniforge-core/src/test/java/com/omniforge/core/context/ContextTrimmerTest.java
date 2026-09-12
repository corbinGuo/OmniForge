package com.omniforge.core.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextTrimmerTest {

    /** 构造 N 对 user/assistant 条目，每条估算指定 token 数（裁剪按条目标注值计算，测试完全确定） */
    private static List<ContextEntry> history(int pairs, int tokensPerEntry) {
        List<ContextEntry> entries = new ArrayList<>();
        for (int i = 1; i <= pairs; i++) {
            entries.add(new ContextEntry(ContextRole.USER, "问题" + i, tokensPerEntry));
            entries.add(new ContextEntry(ContextRole.ASSISTANT, "回答" + i, tokensPerEntry));
        }
        return entries;
    }

    private static ContextSettings settings(int budget, int keepRecent, TrimStrategy strategy) {
        return new ContextSettings(true, budget, keepRecent, strategy, null);
    }

    @Test
    void 未超预算原样返回() {
        ContextTrimmer.TrimResult result = ContextTrimmer.trim(
                history(1, 3_000), "", settings(10_000, 5, TrimStrategy.SLIDING_WINDOW), null, null);
        assertEquals(2, result.kept().size());
        assertEquals(0, result.droppedPairs());
        assertFalse(result.budgetDegraded());
    }

    @Test
    void 超预算从最旧轮对整对删除且不拆对() {
        // 4 对 ×2000 = 8000；预算 5000 → 裁掉最旧 2 对，保留最近 2 对（4000 ≤ 5000）
        ContextTrimmer.TrimResult result = ContextTrimmer.trim(
                history(4, 1_000), "", settings(5_000, 2, TrimStrategy.SLIDING_WINDOW), null, null);
        assertEquals(2, result.droppedPairs());
        assertEquals(4, result.kept().size());
        assertEquals("问题3", result.kept().get(0).content(), "最旧轮对被整对删除，不拆对");
        assertEquals("回答4", result.kept().get(3).content());
        assertFalse(result.budgetDegraded());
    }

    @Test
    void 保近N轮与预算冲突时降级仅保留最后一轮() {
        // 4 对 ×2000 = 8000；预算 3000 连保近 3 轮都不满足 → 放弃保留区，仅保留最后 1 对
        ContextTrimmer.TrimResult result = ContextTrimmer.trim(
                history(4, 1_000), "", settings(3_000, 3, TrimStrategy.SLIDING_WINDOW), null, null);
        assertEquals(3, result.droppedPairs());
        assertEquals(2, result.kept().size());
        assertTrue(result.budgetDegraded(), "放弃保近 N 轮时应标记降级（调用方告警）");
    }

    @Test
    void 当前输入单独超预算时无法裁剪原样返回() {
        String hugeInput = "超长输入".repeat(500); // ≈ 2300 token > 预算
        ContextTrimmer.TrimResult result = ContextTrimmer.trim(
                history(2, 100), hugeInput, settings(1_024, 5, TrimStrategy.SLIDING_WINDOW), null, null);
        assertEquals(4, result.kept().size());
        assertEquals(0, result.droppedPairs());
    }

    @Test
    void 模型窗口参与预算计算() {
        // 4 对 ×4000 = 16000；窗口 10000 → 预算 8000：常规裁剪到最近 2 对（16000-8000=8000 ≤ 8000）
        ContextTrimmer.TrimResult result = ContextTrimmer.trim(
                history(4, 2_000), "", settings(10_000, 2, TrimStrategy.SLIDING_WINDOW), 10_000, null);
        assertEquals(2, result.droppedPairs());
        assertFalse(result.budgetDegraded());
        // 窗口 1000 → 预算 800：保近 2 轮也放不下 → 降级仅保留最后 1 对
        ContextTrimmer.TrimResult tight = ContextTrimmer.trim(
                history(4, 2_000), "", settings(10_000, 2, TrimStrategy.SLIDING_WINDOW), 1_000, null);
        assertEquals(3, tight.droppedPairs());
        assertTrue(tight.budgetDegraded());
    }

    @Test
    void 摘要策略把被裁部分压成system条目置顶() {
        ContextTrimmer.TrimResult result = ContextTrimmer.trim(
                history(4, 3_000), "", settings(8_000, 2, TrimStrategy.SUMMARIZE), null,
                dropped -> "关键结论摘要");
        assertEquals(3, result.droppedPairs());
        assertEquals("关键结论摘要", result.summary());
        assertEquals(3, result.kept().size(), "摘要 SYSTEM 条目 + 保留的最后 1 对");
        assertEquals(ContextRole.SYSTEM, result.kept().get(0).role());
        assertTrue(result.kept().get(0).content().startsWith(ContextTrimmer.SUMMARY_PREFIX));
    }

    @Test
    void 摘要返回空白时降级为纯滑动窗口() {
        ContextTrimmer.TrimResult result = ContextTrimmer.trim(
                history(4, 3_000), "", settings(8_000, 2, TrimStrategy.SUMMARIZE), null,
                dropped -> "   ");
        assertNull(result.summary());
        assertEquals(2, result.kept().size(), "无摘要条目，仅剩最后 1 对");
        assertEquals(ContextRole.USER, result.kept().get(0).role());
    }

    @Test
    void 未超预算时即使摘要策略也不调用摘要器() {
        boolean[] invoked = {false};
        ContextTrimmer.trim(history(1, 3_000), "",
                settings(10_000, 5, TrimStrategy.SUMMARIZE), null, dropped -> {
                    invoked[0] = true;
                    return "摘要";
                });
        assertFalse(invoked[0], "预算内不应触发摘要调用");
    }

    @Test
    void 摘要器为null时摘要策略自动降级() {
        ContextTrimmer.TrimResult result = ContextTrimmer.trim(
                history(4, 3_000), "", settings(8_000, 2, TrimStrategy.SUMMARIZE), null, null);
        assertNull(result.summary());
        assertEquals(2, result.kept().size());
    }
}
