package com.omniforge.core.agent;

/**
 * Agent 运行事件流（Phase 2 P0-14：工具调用可视化的数据源）。
 *
 * <p>双通道设计：文本块与步骤事件同流输出，
 * UI 可同时实现打字机效果与"思考→行动→观察"步骤卡片。</p>
 */
public sealed interface AgentEvent {

    /** 一轮文本块（本轮模型的完整回复；逐 token 流式为后续增量） */
    record TextChunk(String text) implements AgentEvent {
    }

    /** 工具调用开始（行动前） */
    record ToolCallStarted(String toolName, String arguments) implements AgentEvent {
    }

    /** 工具调用结束（观察后，含结果与状态） */
    record ToolCallFinished(ToolCallRecord record) implements AgentEvent {
    }

    /** 运行完成（含完整结果与停止原因） */
    record Completed(AgentRunResult result) implements AgentEvent {
    }

    /** 运行失败 */
    record Failed(String errorMessage) implements AgentEvent {
    }
}
