package com.omniforge.core.agent;

import reactor.core.publisher.Flux;

/**
 * Agent 引擎 SPI（需求 4.2）。
 *
 * <p>默认实现为 {@link ReactAgentLoop}（ReAct 思考-行动-观察循环）；
 * Spring AI Alibaba 内置编排模式（SequentialAgent/ParallelAgent/RoutingAgent/LoopAgent，
 * 用于 Phase 2 辩论引擎）可作为本接口的可替换实现接入。</p>
 *
 * <p>实现约定：
 * <ul>
 *   <li>{@link #run} 为阻塞调用，由调用方决定线程（会话层通常放入虚拟线程）；</li>
 *   <li>模型/工具异常不得逃逸出 run（以 {@link StopReason#MODEL_ERROR} 结果返回）；</li>
 *   <li>同一 runId 不可并发执行（实现需幂等处理 {@link #cancel}）。</li>
 * </ul>
 */
public interface AgentEngine {

    /** 阻塞执行一次 Agent 运行 */
    AgentRunResult run(AgentRunRequest request);

    /**
     * 以事件流执行 Agent 运行（Phase 2 工具调用可视化）：
     * TextChunk（每轮文本）→ ToolCallStarted/Finished（步骤）→ Completed/Failed（终态）。
     * 订阅方取消订阅即中止；打断仍可经 {@link #cancel}。
     */
    Flux<AgentEvent> stream(AgentRunRequest request);

    /** 打断指定运行（幂等；不存在/已结束的 runId 为无害空操作） */
    void cancel(String runId);
}
