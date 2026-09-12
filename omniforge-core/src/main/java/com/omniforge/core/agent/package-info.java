/**
 * Agent 引擎（需求 4.2）。
 *
 * <p>基于 Spring AI 的 ToolCallingChatModel 实现 ReAct（思考-行动-观察）循环，
 * 集成 common 模块的 Tool SPI，内置多重熔断（最大轮次/时间/成本/手动打断）。
 * {@link com.omniforge.core.agent.AgentEngine} 为稳定 SPI：
 * Phase 2 的辩论引擎、Spring AI Alibaba 内置编排模式（Sequential/Parallel/Routing/Loop）
 * 均可作为其可替换实现接入。</p>
 */
package com.omniforge.core.agent;
