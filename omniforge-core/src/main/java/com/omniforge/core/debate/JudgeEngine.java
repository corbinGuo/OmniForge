package com.omniforge.core.debate;

import java.util.Map;

/**
 * 辩论裁判 SPI（需求 4.3 有裁判模式：每轮结束进行"总结归纳"与"裁决投票"）。
 *
 * <p>默认实现 {@code GatewayJudgeEngine}：裁判模型经模型网关注入
 * （提示词要求输出结构化裁决标记【裁决】获胜方：x / 死锁 / 继续）；
 * 测试以假实现替换。</p>
 */
public interface JudgeEngine {

    /** 裁决判定 */
    enum JudgeDecision {
        /** 继续下一轮 */
        CONTINUE,
        /** 宣布获胜方（终止辩论） */
        WINNER,
        /** 判定死锁（终止辩论） */
        DEADLOCK
    }

    /** 裁决结果 */
    record JudgeVerdict(JudgeDecision decision, String winnerAlias, String text) {

        public static JudgeVerdict continueDebate(String text) {
            return new JudgeVerdict(JudgeDecision.CONTINUE, null, text);
        }

        public static JudgeVerdict winner(String alias, String text) {
            return new JudgeVerdict(JudgeDecision.WINNER, alias, text);
        }

        public static JudgeVerdict deadlock(String text) {
            return new JudgeVerdict(JudgeDecision.DEADLOCK, null, text);
        }
    }

    /**
     * 对本轮辩论进行裁决。
     *
     * @param judgeAlias     裁判模型别名（复用已加载模型列表，role="judge"）
     * @param topic          辩论主题
     * @param round          轮次（从 1 开始）
     * @param roundOutputs   本轮各模型发言（键=别名）
     * @param historySummary 此前各轮观点摘要（可为空串）
     */
    JudgeVerdict judge(String judgeAlias, String topic, int round,
                       Map<String, String> roundOutputs, String historySummary);
}
