package com.omniforge.core.gateway;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.omniforge.common.exception.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * models.yml 加载器：解析、校验并输出默认模板（首启时）。
 *
 * <p>配置位置：{@code ~/.omniforge/models.yml}（Linux）/ {@code %APPDATA%\OmniForge\models.yml}（Windows）。
 * 修改保存后由 {@link ModelConfigWatcher} 触发热重载，无需重启。</p>
 */
public final class ModelConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigLoader.class);
    private static final String TEMPLATE_RESOURCE = "models.yml.template";

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 加载配置；文件不存在时先写出默认模板再加载 */
    public ModelGatewayConfig loadOrCreate(Path configFile) {
        Objects.requireNonNull(configFile, "configFile");
        if (!Files.exists(configFile)) {
            writeDefaultTemplate(configFile);
        }
        return load(configFile);
    }

    /** 从文件加载并校验配置（文件不存在时抛 {@link ConfigurationException}） */
    public ModelGatewayConfig load(Path configFile) {
        Objects.requireNonNull(configFile, "configFile");
        try {
            ModelGatewayConfig config = yamlMapper.readValue(Files.readAllBytes(configFile), ModelGatewayConfig.class);
            config.validate();
            log.info("已加载模型配置：{}（{} 个提供商，{} 个模型）",
                    configFile.toAbsolutePath(), config.getProviders().size(), config.getModels().size());
            return config;
        } catch (IOException e) {
            throw new ConfigurationException("模型配置读取失败: " + configFile, e);
        }
    }

    /** 写出默认模板（幂等：已存在则跳过） */
    public void writeDefaultTemplate(Path configFile) {
        Objects.requireNonNull(configFile, "configFile");
        if (Files.exists(configFile)) {
            return;
        }
        try (InputStream in = ModelConfigLoader.class.getClassLoader().getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new ConfigurationException("内置模板缺失: " + TEMPLATE_RESOURCE);
            }
            Path target = configFile.toAbsolutePath();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.copy(in, target);
            log.warn("首次启动：已生成默认模型配置 {}，请配置 API Key（修改保存即自动生效）", target);
        } catch (IOException e) {
            throw new ConfigurationException("默认模板写出失败: " + configFile, e);
        }
    }
}
