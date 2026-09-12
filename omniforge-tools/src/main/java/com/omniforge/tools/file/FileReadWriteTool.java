package com.omniforge.tools.file;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.common.spi.ToolSpec;
import com.omniforge.tools.ToolsProperties;
import com.omniforge.tools.ToolsSettingsHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * file_read_write 内置工具（需求 4.5：文件访问沙箱）。
 * 所有操作经 {@link WorkspaceGuard} 限定在授权目录内：
 * 授权目录列表可在配置中心「文件沙箱根目录」配置（支持多个，热生效）；
 * 空列表 = 仅默认工作区 {@code <配置目录>/workspace}。
 * 绝对路径须落在任一授权目录内；相对路径以默认工作区为根（默认工作区需已授权才可用）。
 * 授权目录即用户显式放开的访问范围，自行承担安全责任。
 */
public final class FileReadWriteTool implements Tool {

    public static final String NAME = "file_read_write";

    /** 单文件读取上限（字节），超出截断并注明 */
    private static final int MAX_READ_BYTES = 1_048_576;

    private final ToolsProperties properties;
    private final ToolsSettingsHolder settingsHolder; // 可为 null（无热重载场景回退 properties）

    public FileReadWriteTool(ToolsProperties properties) {
        this(properties, null);
    }

    public FileReadWriteTool(ToolsProperties properties, ToolsSettingsHolder settingsHolder) {
        this.properties = properties;
        this.settingsHolder = settingsHolder;
    }

    /** 当前授权沙箱根目录：ToolsSettings 目录列表非空 → 用之（热生效）；空 → 默认工作区 */
    private List<Path> authorizedRoots() {
        if (settingsHolder != null) {
            List<String> overrides = settingsHolder.current().workspaceRoots();
            if (!overrides.isEmpty()) {
                return overrides.stream().map(Path::of).toList();
            }
        }
        return List.of(properties.getWorkspaceRoot());
    }

    /**
     * 操作级人工确认（TOOL_CONFIRMATION Q1-B）：
     * 写/追加/删除会改动用户文件 → 需确认；读/列目录为只读 → 不需确认。
     */
    @Override
    public boolean requiresConfirmation(Map<String, Object> params) {
        String operation = params == null ? "" : String.valueOf(params.get("operation"));
        return "write".equals(operation) || "append".equals(operation) || "delete".equals(operation);
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME,
                "文件操作（read/write/append/list/delete）。path 可为绝对路径（须位于任一授权沙箱目录内，"
                        + "如 D:\\data\\a.pdf）或相对路径（以默认工作区为根，不可用 ../ 上浮到其他授权目录）；"
                        + "禁止访问授权目录之外的路径。",
                Map.of("type", "object", "properties", Map.of(
                                "operation", Map.of("type", "string",
                                        "description", "read | write | append | list | delete"),
                                "path", Map.of("type", "string",
                                        "description", "绝对路径（须在授权沙箱目录内）或相对默认工作区的相对路径"),
                                "content", Map.of("type", "string", "description", "write/append 时的内容")),
                        "required", List.of("operation", "path")),
                false);
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String operation = String.valueOf(request.parameter("operation"));
        String pathValue = String.valueOf(request.parameter("path"));
        long start = System.nanoTime();

        Path path;
        try {
            // 每次执行按当前配置解析授权目录与默认工作区（ToolsSettings 热生效；多根 + 绝对/相对）
            path = new WorkspaceGuard(authorizedRoots())
                    .resolveAccessible(pathValue, properties.getWorkspaceRoot());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("路径被沙箱拒绝: " + e.getMessage(), elapsedMs(start));
        }

        try {
            return switch (operation) {
                case "read" -> read(path, start);
                case "write" -> write(path, request.parameter("content"), false, start);
                case "append" -> write(path, request.parameter("content"), true, start);
                case "list" -> list(path, start);
                case "delete" -> delete(path, start);
                default -> ToolResult.failure("不支持的 operation: " + operation
                        + "（可选 read/write/append/list/delete）", elapsedMs(start));
            };
        } catch (IOException e) {
            return ToolResult.failure("文件操作失败: " + e.getMessage(), elapsedMs(start));
        }
    }

    private ToolResult read(Path path, long start) throws IOException {
        if (!Files.exists(path)) {
            return ToolResult.failure("文件不存在: " + path, elapsedMs(start));
        }
        if (Files.isDirectory(path)) {
            return ToolResult.failure("目标是目录，请使用 list: " + path, elapsedMs(start));
        }
        byte[] bytes = Files.readAllBytes(path);
        boolean truncated = bytes.length > MAX_READ_BYTES;
        int length = truncated ? MAX_READ_BYTES : bytes.length;
        String content = new String(bytes, 0, length, StandardCharsets.UTF_8);
        if (truncated) {
            content += "\n...（文件过大已截断，共 " + bytes.length + " 字节）";
        }
        return ToolResult.success(content, elapsedMs(start));
    }

    private ToolResult write(Path path, Object contentValue, boolean append, long start) throws IOException {
        if (contentValue == null) {
            return ToolResult.failure("缺少参数 content", elapsedMs(start));
        }
        String content = contentValue.toString();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        if (append) {
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        return ToolResult.success("已" + (append ? "追加" : "写入") + " " + content.length() + " 字符 → " + path,
                elapsedMs(start));
    }

    private ToolResult list(Path path, long start) throws IOException {
        if (!Files.isDirectory(path)) {
            return ToolResult.failure("不是目录: " + path, elapsedMs(start));
        }
        try (Stream<Path> entries = Files.list(path)) {
            List<String> names = entries
                    .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .sorted()
                    .collect(Collectors.toList());
            return ToolResult.success(names.isEmpty() ? "（空目录）" : String.join("\n", names), elapsedMs(start));
        }
    }

    private ToolResult delete(Path path, long start) throws IOException {
        boolean deleted = Files.deleteIfExists(path);
        return ToolResult.success(deleted ? "已删除: " + path : "路径不存在: " + path, elapsedMs(start));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
