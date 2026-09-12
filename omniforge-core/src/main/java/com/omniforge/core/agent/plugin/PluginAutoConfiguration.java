package com.omniforge.core.agent.plugin;

import com.omniforge.core.agent.ToolRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 插件热加载自动装配（C-tier 批次 3）。
 * 依赖 Agent 装配已提供 {@link ToolRegistry}；omniforge.plugin.enabled=false 可关闭。
 */
@AutoConfiguration(afterName = "com.omniforge.core.agent.AgentAutoConfiguration")
@ConditionalOnClass(ToolRegistry.class)
@EnableConfigurationProperties(PluginProperties.class)
public class PluginAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "omniforge.plugin", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public PluginManager pluginManager(PluginProperties properties, ToolRegistry toolRegistry) {
        return new PluginManager(properties, toolRegistry);
    }
}
