package com.omniforge.tools.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * MCP 设置持久化（mcp.yml，配置目录下，模式同 tools.yml/context.yml）。
 * 文件缺失/损坏时返回空设置；保存自动创建父目录。
 */
public class McpSettingsStore {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 读取设置；文件缺失/损坏时返回空设置 */
    public McpSettings load(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return McpSettings.empty();
        }
        try {
            return yamlMapper.readValue(Files.readAllBytes(file), McpSettings.class);
        } catch (IOException | IllegalArgumentException e) {
            return McpSettings.empty();
        }
    }

    /** 保存设置（自动创建父目录） */
    public void save(Path file, McpSettings settings) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(settings, "settings");
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        yamlMapper.writeValue(file.toFile(), settings);
    }
}
