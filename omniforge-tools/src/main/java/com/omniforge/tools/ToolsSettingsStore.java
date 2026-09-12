package com.omniforge.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 工具开关持久化（tools.yml，配置目录下）。
 * 文件缺失时返回默认值（shell 禁用 / python 启用 / 空沙箱目录列表）。
 *
 * <p>兼容：历史单值格式 {@code workspaceRoot: "..."}（2026-09-02 前）在 load 时迁移为
 * 多值 {@code workspaceRoots: [...]}；shell/python 开关缺省保持默认语义，读取失败/损坏不丢值。</p>
 */
public class ToolsSettingsStore {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** 读取工具设置；文件缺失/损坏/根非对象时返回默认值，绝不抛出 */
    public ToolsSettings load(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return ToolsSettings.defaults();
        }
        try {
            return fromNode(yamlMapper.readTree(Files.readAllBytes(file)));
        } catch (IOException e) {
            return ToolsSettings.defaults();
        }
    }

    /** readTree 归一化：容忍未知键、标量/数组形态、缺省布尔与旧键迁移，不引入额外 DTO */
    private static ToolsSettings fromNode(JsonNode root) {
        ToolsSettings defaults = ToolsSettings.defaults();
        if (root == null || !root.isObject()) {
            return defaults;
        }
        boolean shell = root.path("shellEnabled").asBoolean(defaults.shellEnabled());
        boolean python = root.path("pythonEnabled").asBoolean(defaults.pythonEnabled());
        List<String> roots = new ArrayList<>();
        JsonNode plural = root.path("workspaceRoots");
        if (plural.isArray()) {
            plural.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) {
                    roots.add(node.asText());
                }
            });
        } else if (plural.isTextual() && !plural.asText().isBlank()) {
            roots.add(plural.asText()); // 手写标量容错
        } else {
            JsonNode legacy = root.path("workspaceRoot");
            if (legacy.isTextual() && !legacy.asText().isBlank()) {
                roots.add(legacy.asText()); // 旧 392da58 单值迁移
            }
        }
        return new ToolsSettings(shell, python, roots);
    }

    /** 保存工具开关（自动创建父目录；record 紧凑构造保证列表已 strip/滤空/去重） */
    public void save(Path file, ToolsSettings settings) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(settings, "settings");
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        yamlMapper.writeValue(file.toFile(), settings);
    }
}
