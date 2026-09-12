package com.omniforge.tools.shell;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import com.omniforge.tools.ToolsProperties;
import com.omniforge.tools.ToolsSettingsHolder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * shell_executor 内置工具（需求 4.5：默认禁用，需手动开启）。
 *
 * <p>安全约束：
 * <ul>
 *   <li>默认禁用（omniforge.tools.shell-enabled=false），开启后仍需通过黑名单；</li>
 *   <li>输出截断至 64KB 防止撑爆上下文；</li>
 *   <li>超时强制销毁进程。</li>
 * </ul>
 */
public final class ShellExecutorTool implements Tool {

    public static final String NAME = "shell_executor";

    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private final ToolsProperties properties;
    private final ToolsSettingsHolder settingsHolder; // 可为 null（无热重载场景回退 properties）

    public ShellExecutorTool(ToolsProperties properties) {
        this(properties, null);
    }

    public ShellExecutorTool(ToolsProperties properties, ToolsSettingsHolder settingsHolder) {
        this.properties = properties;
        this.settingsHolder = settingsHolder;
    }

    private boolean shellEnabled() {
        return settingsHolder != null ? settingsHolder.current().shellEnabled() : properties.isShellEnabled();
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME,
                "执行系统 Shell 命令（危险：默认禁用，开启后受黑名单与超时约束）。",
                Map.of("type", "object", "properties", Map.of(
                        "command", Map.of("type", "string", "description", "要执行的命令")),
                        "required", List.of("command")),
                true); // 需人工确认（需求 4.5 安全约束）
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        long start = System.nanoTime();
        if (!shellEnabled()) {
            return ToolResult.failure("shell_executor 默认禁用（安全约束）。如需开启："
                    + "omniforge.tools.shell-enabled=true，并自行承担风险", elapsedMs(start));
        }
        Object commandValue = request.parameter("command");
        if (commandValue == null || commandValue.toString().isBlank()) {
            return ToolResult.failure("缺少参数 command", elapsedMs(start));
        }
        String command = commandValue.toString();

        String violation = ShellGuard.findViolation(command, properties.getShellBlacklist());
        if (violation != null) {
            return ToolResult.failure("命令被黑名单拦截（命中：" + violation + "）", elapsedMs(start));
        }

        ProcessBuilder builder = isWindows()
                ? new ProcessBuilder("cmd", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread pump = Thread.ofVirtual().start(() -> {
                try {
                    process.getInputStream().transferTo(output);
                } catch (Exception ignored) {
                    // 进程销毁后流读取失败属预期
                }
            });
            boolean finished = process.waitFor(properties.getShellTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.timeout("命令执行超时（>" + properties.getShellTimeoutSeconds() + "s），已强制终止",
                        elapsedMs(start));
            }
            pump.join(2_000);
            int exitCode = process.exitValue();
            String out = truncate(output.toString(StandardCharsets.UTF_8));
            if (exitCode != 0) {
                return ToolResult.failure("退出码 " + exitCode + ":\n" + out, elapsedMs(start));
            }
            return ToolResult.success(out, elapsedMs(start));
        } catch (Exception e) {
            return ToolResult.failure("命令执行失败: " + e.getMessage(), elapsedMs(start));
        }
    }

    private static String truncate(String output) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_OUTPUT_BYTES) {
            return output;
        }
        return new String(bytes, 0, MAX_OUTPUT_BYTES, StandardCharsets.UTF_8)
                + "\n...（输出过大已截断）";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
