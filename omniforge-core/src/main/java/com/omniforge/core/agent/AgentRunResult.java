package com.omniforge.core.agent;

import java.util.List;

/**
 * Agent 运行结果。
 *
 * @param runId        运行标识（与请求一致）
 * @param text         最终回复文本（熔断/异常时为已产生的最后文本或空串）
 * @param steps        每一步的思考与工具调用记录（辩论记录/审计的素材）
 * @param rounds       实际执行的轮次数
 * @param inputTokens  累计输入 token
 * @param outputTokens 累计输出 token
 * @param stopReason   停止原因
 * @param durationMs   总耗时（毫秒）
 * @param errorMessage 错误信息（仅 MODEL_ERROR 时非空）
 */
public record AgentRunResult(String runId, String text, List<AgentStep> steps, int rounds,
                             long inputTokens, long outputTokens, StopReason stopReason,
                             long durationMs, String errorMessage) {
}
