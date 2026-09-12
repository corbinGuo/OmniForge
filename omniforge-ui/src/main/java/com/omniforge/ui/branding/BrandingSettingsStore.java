package com.omniforge.ui.branding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 白标设置持久化（branding.yml，配置目录下）。
 * 文件缺失/损坏时返回默认值（官方品牌）。
 */
public class BrandingSettingsStore {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** 读取白标设置；文件缺失/损坏时返回默认值 */
    public BrandingSettings load(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return BrandingSettings.defaults();
        }
        try {
            return yamlMapper.readValue(Files.readAllBytes(file), BrandingSettings.class);
        } catch (IOException | IllegalArgumentException e) {
            return BrandingSettings.defaults();
        }
    }

    /** 保存白标设置到 branding.yml（父目录不存在自动创建；覆盖写） */
    public void save(Path file, BrandingSettings settings) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(settings, "settings");
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, yamlMapper.writeValueAsBytes(settings));
    }
}
