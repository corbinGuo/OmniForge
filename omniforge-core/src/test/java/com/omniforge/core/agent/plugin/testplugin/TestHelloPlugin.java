package com.omniforge.core.agent.plugin.testplugin;

import com.omniforge.common.spi.OmniForgePlugin;
import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;

import java.util.List;
import java.util.Map;

/** 测试插件 v1：提供 plugin_hello 工具（仅测试源码内使用，运行时打包进 jar 验证热加载） */
public class TestHelloPlugin implements OmniForgePlugin {

    public static final String TOOL_NAME = "plugin_hello";

    @Override
    public String getName() {
        return "test-hello";
    }

    @Override
    public List<Tool> getTools() {
        return List.of(new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(TOOL_NAME, "测试插件：返回问候语",
                        Map.of("message", Map.of("type", "string")), false);
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return ToolResult.success("hello from plugin", 1);
            }
        });
    }
}
