package com.omniforge.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** current_datetime 工具（TOOL_CONFIRMATION A2）单测：零参数输出时间信息。 */
class CurrentTimeToolTest {

    private final CurrentTimeTool tool = new CurrentTimeTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 输出JSON含local偏移与epoch三键() throws Exception {
        ToolResult result = tool.execute(new ToolRequest(Map.of(), null, Map.of()));
        assertThat(result.status()).isEqualTo(ToolResult.ToolStatus.SUCCESS);
        JsonNode node = mapper.readTree(result.output());
        assertThat(node.has("local")).isTrue();
        assertThat(node.has("offset")).isTrue();
        assertThat(node.has("zone")).isTrue();
        assertThat(node.has("epochMs")).isTrue();
        // ISO-8601 本地时间形如 2026-09-11T14:32:05（可带小数秒）
        assertThat(node.get("local").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$");
    }

    @Test
    void epoch与系统当前时间误差小于2秒() throws Exception {
        long before = System.currentTimeMillis();
        ToolResult result = tool.execute(new ToolRequest(Map.of(), null, Map.of()));
        long after = System.currentTimeMillis();
        long epoch = mapper.readTree(result.output()).get("epochMs").asLong();
        assertThat(epoch).isBetween(before - 2000, after + 2000);
    }

    @Test
    void 空参执行不抛异常() {
        ToolResult result = tool.execute(new ToolRequest(null, null, Map.of()));
        assertThat(result.status()).isEqualTo(ToolResult.ToolStatus.SUCCESS);
        assertThat(result.output()).isNotBlank();
    }
}
