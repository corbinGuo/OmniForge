package com.omniforge.core.debate;

/**
 * 辩论运行事件流（Phase 2 多列流式渲染的数据源）。
 * UI 按模型别名分列展示，逐轮逐模型实时追加。
 */
public sealed interface DebateEvent {

    /** 新一轮开始 */
    record RoundStarted(int round) implements DebateEvent {
    }

    /** 某模型本轮的增量文本块（逐 token 流式渲染） */
    record ModelChunk(String modelAlias, int round, String chunk) implements DebateEvent {
    }

    /** 某模型本轮发言完成（携带完整文本，供记录/后续共识判定） */
    record ModelText(String modelAlias, int round, String text) implements DebateEvent {
    }

    /** 辩论完成（含完整结果） */
    record Completed(DebateResult result) implements DebateEvent {
    }

    /** 辩论失败 */
    record Failed(String errorMessage) implements DebateEvent {
    }

    /** 裁判评语（有裁判模式每轮强制输出；含总结归纳与裁决） */
    record JudgeVerdict(int round, String verdict) implements DebateEvent {
    }
}
