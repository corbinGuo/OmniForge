package com.omniforge.core.audit;

import java.time.Instant;
import java.util.List;

/**
 * 审计日志条目（C-tier 批次 4：Agent 调用链结构化记录）。
 *
 * @param timestamp    记录时间（UTC）
 * @param user         用户标识（GUI 为本机用户名；IM 为消息来源）
 * @param sessionId    会话标识（ui:default / im:平台:chatId）
 * @param runId        Agent 运行标识（AgentRunRequest.runId）
 * @param modelAlias   实际使用的模型别名
 * @param requestType  请求类型（agent/chat/debate）
 * @param inputText    用户输入（审计原文，超长截断由写入方控制）
 * @param stopReason   终止原因（COMPLETED/TIMEOUT/COST_LIMIT/…）
 * @param durationMs   总耗时（毫秒）
 * @param inputTokens  输入 token 数（模型元数据累计）
 * @param outputTokens 输出 token 数（模型元数据累计）
 * @param costUsd      估算成本（美元，按模型定价计算）
 * @param toolCalls    工具调用明细（按执行顺序）
 */
public record AuditEntry(Instant timestamp, String user, String sessionId, String runId,
                         String modelAlias, String requestType, String inputText,
                         String stopReason, long durationMs, long inputTokens,
                         long outputTokens, double costUsd, List<AuditToolCall> toolCalls) {

    public AuditEntry {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 工具调用明细 */
    public record AuditToolCall(String toolName, String arguments, String result,
                                String status, long durationMs) {
    }
}
