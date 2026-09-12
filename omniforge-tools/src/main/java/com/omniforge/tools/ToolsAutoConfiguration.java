package com.omniforge.tools;

import com.omniforge.tools.builtin.BuiltinToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工具生态自动装配：绑定 {@link ToolsProperties} 并暴露内置工具提供者。
 * Agent 引擎的 {@code DefaultToolRegistry} 会经 ServiceLoader 与 Spring Bean 双重渠道聚合工具。
 */
@AutoConfiguration
@EnableConfigurationProperties(ToolsProperties.class)
public class ToolsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolsAutoConfiguration.class);

    /**
     * 工具运行时持有器（配置中心保存后热生效，2.5）。
     *
     * <p>播种（2026-09 修复跨重启持久）：tools.yml 存在 → 文件优先加载（含旧单值 workspaceRoot 迁移）；
     * 不存在 → 回退 {@code ToolsSettings.from(properties)}（application.yml/env 默认覆盖仍生效）。
     * 文件存在但读取异常 → 告警并回退默认，绝不阻断启动。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolsSettingsHolder toolsSettingsHolder(ToolsProperties properties) {
        return new ToolsSettingsHolder(loadSeed(properties));
    }

    @Bean
    @ConditionalOnMissingBean
    public BuiltinToolProvider builtinToolProvider(ToolsProperties properties, ToolsSettingsHolder holder) {
        return new BuiltinToolProvider(properties, holder);
    }

    private static ToolsSettings loadSeed(ToolsProperties properties) {
        Path file = properties.getConfigFile();
        if (file != null && Files.exists(file)) {
            try {
                // ToolsSettingsStore.load 自身对损坏/缺失容忍返回默认；此处仅防御意外运行时异常
                return new ToolsSettingsStore().load(file);
            } catch (RuntimeException e) {
                log.warn("tools.yml 读取失败，回退默认配置：{}", e.getMessage());
            }
        }
        return ToolsSettings.from(properties);
    }
}
