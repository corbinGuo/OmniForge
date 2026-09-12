package com.omniforge.core.agent;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolProvider;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultToolRegistryTest {

    @Test
    void 聚合提供者并按名称查找() {
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(
                provider(tool("tool-a"), tool("tool-b")),
                provider(tool("tool-c"))
        ));

        assertEquals(3, registry.all().size());
        assertTrue(registry.find("tool-a").isPresent());
        assertTrue(registry.find("no-such").isEmpty());
    }

    private static ToolProvider provider(Tool... tools) {
        return new ToolProvider() {
            @Override
            public String name() {
                return "test-provider";
            }

            @Override
            public List<Tool> tools() {
                return List.of(tools);
            }
        };
    }

    @Test
    void 重名工具保留先注册者() {
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of());

        assertTrue(registry.register(tool("dup")));
        assertFalse(registry.register(tool("dup")), "重名工具应被忽略");
        assertEquals(1, registry.all().size());
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, "测试工具 " + name, Map.of("type", "object"), false);
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return ToolResult.success(name, 0);
            }
        };
    }
}
