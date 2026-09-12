package com.omniforge.tools.builtin;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * current_datetime 内置工具（TOOL_CONFIRMATION A2 / SYSTEM_DESIGN 对照表 #A2）：
 * 零参数返回当前系统本地时间（ISO-8601）、时区偏移与 epoch 毫秒，供 Agent 做时间相关推断。
 */
public final class CurrentTimeTool implements Tool {

    public static final String NAME = "current_datetime";

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME,
                "返回当前系统本地时间（ISO-8601）、时区偏移与 epoch 毫秒，用于时间相关推断。零参数。",
                Map.of("type", "object", "properties", Map.of()),
                false);
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        ZonedDateTime now = ZonedDateTime.now();
        String local = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String offset = now.getOffset().getId();
        String zone = ZoneId.systemDefault().getId();
        long epochMs = now.toInstant().toEpochMilli();
        String output = "{\"local\":\"" + local + "\",\"offset\":\"" + offset
                + "\",\"zone\":\"" + zone + "\",\"epochMs\":" + epochMs + "}";
        return ToolResult.success(output, 0);
    }
}
