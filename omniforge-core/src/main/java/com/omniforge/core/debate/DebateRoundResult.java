package com.omniforge.core.debate;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 单轮辩论结果：各模型本轮发言（键为模型别名，顺序与请求一致）+ 轮次完成时间（记录/重播用）。
 */
public record DebateRoundResult(int round, Map<String, String> modelTexts, LocalDateTime completedAt) {

    public DebateRoundResult {
        modelTexts = Map.copyOf(modelTexts);
    }
}
