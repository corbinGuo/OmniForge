package com.omniforge.core.agent;

import java.util.List;

/**
 * Agent 单步记录（一轮 ReAct 循环中的"行动+观察"）。
 *
 * @param round         轮次序号（从 1 开始）
 * @param assistantText 该轮模型的思考/回复文本
 * @param toolCalls     该轮的工具调用及观察结果（无工具调用的最终轮为空列表）
 */
public record AgentStep(int round, String assistantText, List<ToolCallRecord> toolCalls) {

    public AgentStep {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
