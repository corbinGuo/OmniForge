package com.omniforge.core.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolApproval;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OmniForgeToolCallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Tool toolWithSchema(Map<String, Object> schema) {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("demo", "demo", schema, false);
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return ToolResult.success("ok", 1);
            }
        };
    }

    private Map<String, Object> schemaOf(Tool tool) throws Exception {
        String json = new OmniForgeToolCallback(tool).getToolDefinition().inputSchema();
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    @Test
    void 空参数自动补全object类型壳() throws Exception {
        Map<String, Object> schema = schemaOf(toolWithSchema(Map.of()));
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat((Map<?, ?>) schema.get("properties")).isEmpty();
        assertThat(schema.get("additionalProperties")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void 缺壳仅properties也补全object类型() throws Exception {
        Map<String, Object> schema = schemaOf(toolWithSchema(Map.of(
                "keyword", Map.of("type", "string", "description", "关键词"))));
        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKey("keyword");
    }

    @Test
    void 完整schema原样透传不被二次包裹() throws Exception {
        Map<String, Object> full = Map.of("type", "object", "properties", Map.of(
                "x", Map.of("type", "string")));
        Map<String, Object> schema = schemaOf(toolWithSchema(full));
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema).hasSize(2); // 不额外追加 additionalProperties
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKey("x");
    }

    // ---------- HITL 确认（TOOL_CONFIRMATION A1） ----------

    /** 需人工确认的演示工具（spec 标志 = true） */
    private Tool confirmTool() {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("danger_tool", "危险操作", Map.of("type", "object"), true);
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return ToolResult.success("executed", 1);
            }
        };
    }

    @Test
    void 需确认且审批拒绝时返回拒绝文本且不执行() throws Exception {
        ToolApproval approval = mock(ToolApproval.class);
        when(approval.approve(any(), any())).thenReturn(false);
        Tool tool = confirmTool();
        OmniForgeToolCallback callback =
                new OmniForgeToolCallback(tool, mapper, approval, true);

        String result = callback.call("{}");

        assertThat(result).isEqualTo("ERROR: 用户拒绝执行 danger_tool");
        verify(approval).approve(any(), any());
    }

    @Test
    void 需确认且审批允许时正常执行() throws Exception {
        ToolApproval approval = mock(ToolApproval.class);
        when(approval.approve(any(), any())).thenReturn(true);
        OmniForgeToolCallback callback =
                new OmniForgeToolCallback(confirmTool(), mapper, approval, true);

        assertThat(callback.call("{}")).isEqualTo("executed");
    }

    @Test
    void 无确认通道时直接执行不抛异常() throws Exception {
        // approval=null（Headless/无 GUI，Q3-A 放行）
        OmniForgeToolCallback callback =
                new OmniForgeToolCallback(confirmTool(), mapper, null, true);

        assertThat(callback.call("{}")).isEqualTo("executed");
    }

    @Test
    void 非交互身份时不查询确认通道() throws Exception {
        ToolApproval approval = mock(ToolApproval.class);
        OmniForgeToolCallback callback =
                new OmniForgeToolCallback(confirmTool(), mapper, approval, false);

        assertThat(callback.call("{}")).isEqualTo("executed");
        verify(approval, never()).approve(any(), any());
    }
}
