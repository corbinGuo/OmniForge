/**
 * 多模型辩论引擎（需求 4.3，Phase 2）。
 *
 * <p>架构（v5.2 冻结）：自研 {@link com.omniforge.core.debate.DebateEngine}
 * （轮次状态机/熔断/共识判定/裁判裁决/记录）+ SAA ParallelAgent 每轮动态构建
 * （subAgents 按用户所选模型运行时创建，每个子 Agent 经 Builder.model 绑定各自 ChatModel）。
 * Phase 2.2 实现无裁判最小闭环；语义共识/单调性（2.3）与裁判模式（2.4）随后接入。</p>
 */
package com.omniforge.core.debate;
