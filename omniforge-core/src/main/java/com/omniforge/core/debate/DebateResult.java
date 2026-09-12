package com.omniforge.core.debate;

import com.omniforge.core.agent.StopReason;

import java.util.List;

/**
 * 辩论运行结果（2.7 起自包含记录所需的全部上下文，可直接落库）。
 *
 * @param sessionId     会话 ID
 * @param topic         辩论主题
 * @param modelAliases  参与模型别名（顺序即展示顺序）
 * @param judgeAlias    裁判模型别名（无裁判模式为 null）
 * @param maxRounds     最大轮次
 * @param rounds        逐轮结果（辩论记录/导出的数据源）
 * @param judgeVerdicts 裁判评语（有裁判模式逐轮强制记录；无裁判模式为空列表）
 * @param winnerAlias   获胜方别名（仅 JUDGE_WINNER 时非空）
 * @param stopReason    停止原因（复用 Agent 引擎的 StopReason）
 * @param durationMs    总耗时（毫秒）
 * @param errorMessage  错误信息（仅失败时非空）
 */
public record DebateResult(String sessionId, String topic, List<String> modelAliases,
                           String judgeAlias, int maxRounds, String discussionMode,
                           List<DebateRoundResult> rounds, List<JudgeVerdictEntry> judgeVerdicts,
                           String winnerAlias, StopReason stopReason,
                           long durationMs, String errorMessage) {

    /** 单条裁判评语 */
    public record JudgeVerdictEntry(int round, String verdict) {
    }
}
