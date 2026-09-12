package com.omniforge.core.debate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 裁判裁决标记解析测试（结构化输出协议）。 */
class GatewayJudgeEngineTest {

    @Test
    void 解析获胜方标记() {
        JudgeEngine.JudgeVerdict verdict = GatewayJudgeEngine.parseVerdict(
                "总结：a 方论据更充分。\n【裁决】获胜方：a");
        assertEquals(JudgeEngine.JudgeDecision.WINNER, verdict.decision());
        assertEquals("a", verdict.winnerAlias());
    }

    @Test
    void 解析死锁标记() {
        JudgeEngine.JudgeVerdict verdict = GatewayJudgeEngine.parseVerdict(
                "各方观点重复。\n【裁决】死锁");
        assertEquals(JudgeEngine.JudgeDecision.DEADLOCK, verdict.decision());
        assertNull(verdict.winnerAlias());
    }

    @Test
    void 解析继续标记() {
        JudgeEngine.JudgeVerdict verdict = GatewayJudgeEngine.parseVerdict(
                "本局尚无定论。\n【裁决】继续");
        assertEquals(JudgeEngine.JudgeDecision.CONTINUE, verdict.decision());
    }

    @Test
    void 缺失标记保守按继续处理() {
        JudgeEngine.JudgeVerdict verdict = GatewayJudgeEngine.parseVerdict("这是一段没有标记的评语");
        assertEquals(JudgeEngine.JudgeDecision.CONTINUE, verdict.decision());
        assertEquals(JudgeEngine.JudgeDecision.CONTINUE, GatewayJudgeEngine.parseVerdict("").decision());
        assertEquals(JudgeEngine.JudgeDecision.CONTINUE, GatewayJudgeEngine.parseVerdict(null).decision());
    }
}
