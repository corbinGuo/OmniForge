package com.omniforge.ui.theme;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 界面设置持久化（ui.yml，配置目录下，模式同 tools.yml/context.yml）。
 * 文件缺失/损坏时返回默认值；保存自动创建父目录。
 */
public class UiSettingsStore {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** 读取设置；文件缺失/损坏时返回默认值 */
    public UiSettings load(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return UiSettings.defaults();
        }
        try {
            return yamlMapper.readValue(Files.readAllBytes(file), UiSettings.class);
        } catch (IOException | IllegalArgumentException e) {
            return UiSettings.defaults();
        }
    }

    /** 保存设置（自动创建父目录） */
    public void save(Path file, UiSettings settings) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(settings, "settings");
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        yamlMapper.writeValue(file.toFile(), settings);
    }
}
