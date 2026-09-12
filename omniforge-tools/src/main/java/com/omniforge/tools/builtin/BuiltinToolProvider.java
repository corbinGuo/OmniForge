package com.omniforge.tools.builtin;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolProvider;
import com.omniforge.tools.ToolsProperties;
import com.omniforge.tools.file.FileReadWriteTool;
import com.omniforge.tools.python.PythonTool;
import com.omniforge.tools.shell.ShellExecutorTool;
import com.omniforge.tools.web.WebSearchTool;

import java.util.List;

/**
 * 内置工具提供者：web_search / file_read_write / python_interpreter / shell_executor。
 *
 * <p>通过 META-INF/services 注册（ServiceLoader 自动发现，供 Agent 引擎工具注册表聚合）；
 * Spring 环境中另经 {@code ToolsAutoConfiguration} 以配置化实例装配。</p>
 */
public class BuiltinToolProvider implements ToolProvider {

    private final List<Tool> tools;

    /** 无参构造（ServiceLoader 使用）：采用默认配置（读取环境变量） */
    public BuiltinToolProvider() {
        this(new ToolsProperties());
    }

    /** 配置化构造（Spring 装配使用） */
    public BuiltinToolProvider(ToolsProperties properties) {
        this(properties, null);
    }

    /** 带工具开关持有器的构造（配置中心热重载生效） */
    public BuiltinToolProvider(ToolsProperties properties, com.omniforge.tools.ToolsSettingsHolder settingsHolder) {
        this.tools = List.of(
                new WebSearchTool(properties),
                new FileReadWriteTool(properties, settingsHolder),
                new PythonTool(properties, settingsHolder),
                new ShellExecutorTool(properties, settingsHolder),
                new CurrentTimeTool());
    }

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public List<Tool> tools() {
        return tools;
    }
}
