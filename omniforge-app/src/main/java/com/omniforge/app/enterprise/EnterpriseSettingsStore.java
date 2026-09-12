package com.omniforge.app.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 企业版设置持久化（enterprise.yml，与 models.yml/context.yml 同目录）。
 * 文件缺失/损坏时返回默认值。
 */
public class EnterpriseSettingsStore {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** 读取设置；文件缺失/损坏时返回默认值 */
    public EnterpriseSettings load(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return EnterpriseSettings.defaults();
        }
        try {
            return yamlMapper.readValue(Files.readAllBytes(file), EnterpriseSettings.class);
        } catch (IOException | IllegalArgumentException e) {
            return EnterpriseSettings.defaults();
        }
    }

    /** 保存设置（自动创建父目录；登录框保存服务端地址时调用） */
    public void save(Path file, EnterpriseSettings settings) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(settings, "settings");
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        yamlMapper.writeValue(file.toFile(), settings);
    }
}
