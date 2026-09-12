package com.omniforge.tools.python;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import com.omniforge.tools.ToolsProperties;
import com.omniforge.tools.guard.PythonGuard;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * python_interpreter 内置工具（需求 4.5：超时杀进程，黑名单拦截危险命令）。
 *
 * <p>引擎经 {@link PythonEngine#discover()} 动态发现；代码在执行前经黑名单正则校验。</p>
 */
public final class PythonTool implements Tool {

    public static final String NAME = "python_interpreter";

    private final ToolsProperties properties;
    private final com.omniforge.tools.ToolsSettingsHolder settingsHolder; // 可为 null

    public PythonTool(ToolsProperties properties) {
        this(properties, null);
    }

    public PythonTool(ToolsProperties properties, com.omniforge.tools.ToolsSettingsHolder settingsHolder) {
        this.properties = properties;
        this.settingsHolder = settingsHolder;
    }

    private boolean pythonEnabled() {
        return settingsHolder != null ? settingsHolder.current().pythonEnabled() : properties.isPythonEnabled();
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME,
                "执行 Python 代码（进程内 CPython）。安全策略：危险语句黑名单拦截 + 超时强制终止。",
                Map.of("type", "object", "properties", Map.of(
                        "code", Map.of("type", "string", "description", "要执行的 Python 代码")),
                        "required", List.of("code")),
                false);
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        if (!pythonEnabled()) {
            return ToolResult.failure("python_interpreter 已禁用（omniforge.tools.python-enabled=false）", 0);
        }
        Object codeValue = request.parameter("code");
        if (codeValue == null || codeValue.toString().isBlank()) {
            return ToolResult.failure("缺少参数 code", 0);
        }
        String code = codeValue.toString();

        List<Pattern> blacklist = properties.pythonBlacklistPatterns();
        String violation = PythonGuard.findViolation(code, blacklist);
        if (violation != null) {
            return ToolResult.failure("代码被安全策略拦截（命中黑名单：" + violation + "）", 0);
        }

        PythonEngine engine = PythonEngine.discover().orElse(null);
        if (engine == null) {
            return ToolResult.failure("Python 引擎未安装：请以 mvn -P python 构建 omniforge-tools-python 模块（JEP），"
                    + "并确保本机安装 CPython", 0);
        }
        if (!engine.isAvailable()) {
            return ToolResult.failure("Python 引擎不可用：" + engine.version(), 0);
        }

        PythonResult result = engine.execute(code, properties.getPythonTimeoutSeconds() * 1000L);
        return switch (result.status()) {
            case SUCCESS -> ToolResult.success(result.output(), result.durationMs());
            case TIMEOUT -> ToolResult.timeout("执行超时（>" + properties.getPythonTimeoutSeconds() + "s）："
                    + result.error(), result.durationMs());
            case ERROR -> ToolResult.failure(result.error(), result.durationMs());
        };
    }
}
