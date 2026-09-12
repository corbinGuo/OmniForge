package com.omniforge.core.agent;

/** Agent 运行停止原因（对应需求 4.2 的停止逻辑/多重熔断）。 */
public enum StopReason {

    /** 正常完成（模型给出最终回复，无待执行工具调用） */
    COMPLETED,

    /** 达到最大轮次（用户预设的讨论/推理轮次耗尽） */
    MAX_ROUNDS,

    /** 时间熔断：整体运行超时 */
    TIMEOUT,

    /** 成本熔断：累计估算成本超过预算 */
    COST_LIMIT,

    /** 用户手动打断（UI/IM 的"停止讨论"） */
    INTERRUPTED,

    /** 模型调用异常（单模型故障隔离：以结果形式返回，不向上抛出） */
    MODEL_ERROR,

    /** 语义共识达成：多模型回复相似度超过阈值（需求 4.2） */
    CONSENSUS,

    /** 观点单调性：连续多轮无新内容，"集体哑火"（需求 4.2） */
    STALEMATE,

    /** 裁判宣布获胜方（需求 4.3 有裁判模式） */
    JUDGE_WINNER,

    /** 裁判判定死锁（需求 4.3 有裁判模式） */
    JUDGE_DEADLOCK
}
