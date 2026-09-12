package com.omniforge.core.persistence.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单轮辩论归档数据（rounds_json 的类型化视图，Jackson 序列化）。
 */
public record DebateRoundData(int round, LocalDateTime completedAt,
                              Map<String, String> outputs, String judgeVerdict) {

    public DebateRoundData {
        outputs = new LinkedHashMap<>(outputs == null ? Map.of() : outputs);
    }
}
