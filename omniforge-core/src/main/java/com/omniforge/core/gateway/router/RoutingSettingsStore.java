package com.omniforge.core.gateway.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 路由规则持久化（routing.yml，配置目录下）。
 *
 * <p>文件缺失时写入默认模板并返回默认值（本轮无路由规则 UI，模板即用户编辑入口）；
 * 损坏时返回默认值。</p>
 */
public class RoutingSettingsStore {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** 读取规则；文件缺失 → 写入默认模板并返回默认值；损坏 → 返回默认值 */
    public RoutingSettings load(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            try {
                save(file, RoutingSettings.defaults());
            } catch (IOException e) {
                // 模板写入失败不影响默认行为
            }
            return RoutingSettings.defaults();
        }
        try {
            return yamlMapper.readValue(Files.readAllBytes(file), RoutingSettings.class);
        } catch (IOException | IllegalArgumentException e) {
            return RoutingSettings.defaults();
        }
    }

    /** 保存规则（自动创建父目录） */
    public void save(Path file, RoutingSettings settings) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(settings, "settings");
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        yamlMapper.writeValue(file.toFile(), settings);
    }
}
