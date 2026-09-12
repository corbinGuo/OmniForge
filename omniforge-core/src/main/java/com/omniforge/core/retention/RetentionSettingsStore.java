package com.omniforge.core.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 数据保留策略持久化（retention.yml，配置目录下；DATA_RETENTION A3）。
 * 文件缺失/损坏返回默认值（关闭 + 90/90/180），绝不抛出；旧文件缺字段 null 向后兼容。
 */
public class RetentionSettingsStore {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** 读取设置；缺失/损坏/根非对象返回默认值 */
    public RetentionSettings load(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return RetentionSettings.defaults();
        }
        try {
            return fromNode(yamlMapper.readTree(Files.readAllBytes(file)));
        } catch (IOException e) {
            return RetentionSettings.defaults();
        }
    }

    /** readTree 归一化：容忍未知键、缺字段 null 兼容（Q1 默认值兜底） */
    private static RetentionSettings fromNode(JsonNode root) {
        RetentionSettings defaults = RetentionSettings.defaults();
        if (root == null || !root.isObject()) {
            return defaults;
        }
        boolean enabled = root.path("enabled").asBoolean(defaults.enabled());
        int chatDays = root.path("chatDays").isValueNode()
                ? root.path("chatDays").asInt(defaults.chatDays()) : defaults.chatDays();
        int logDays = root.path("logDays").isValueNode()
                ? root.path("logDays").asInt(defaults.logDays()) : defaults.logDays();
        int auditDays = root.path("auditDays").isValueNode()
                ? root.path("auditDays").asInt(defaults.auditDays()) : defaults.auditDays();
        return new RetentionSettings(enabled, chatDays, logDays, auditDays);
    }

    /** 保存（自动创建父目录） */
    public void save(Path file, RetentionSettings settings) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(settings, "settings");
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        yamlMapper.writeValue(file.toFile(), settings);
    }
}
