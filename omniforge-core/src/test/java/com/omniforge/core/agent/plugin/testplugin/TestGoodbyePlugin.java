package com.omniforge.core.agent.plugin.testplugin;

import com.omniforge.common.spi.OmniForgePlugin;
import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;

import java.util.List;
import java.util.Map;

/** 测试插件 v2：提供 plugin_goodbye 工具（验证 jar 内容变化触发重载） */
public class TestGoodbyePlugin implements OmniForgePlugin {

    public static final String TOOL_NAME = "plugin_goodbye";

    @Override
    public String getName() {
        return "test-goodbye";
    }

    @Override
    public List<Tool> getTools() {
        return List.of(new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(TOOL_NAME, "测试插件：返回告别语",
                        Map.of("message", Map.of("type", "string")), false);
            }

            @Override
            public ToolResult execute(ToolRequest request) {
                return ToolResult.success("goodbye from plugin", 1);
            }
        });
    }
}
