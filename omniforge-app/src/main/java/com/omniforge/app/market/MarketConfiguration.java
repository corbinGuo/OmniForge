package com.omniforge.app.market;

import com.omniforge.app.CoreServicesConfiguration;
import com.omniforge.core.agent.plugin.PluginManager;
import com.omniforge.core.agent.plugin.PluginProperties;
import com.omniforge.tools.mcp.McpSettingsHolder;
import com.omniforge.tools.mcp.McpSettingsStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 插件市场装配（P1-3）：GUI / Headless 共用。
 * 目录默认取配置目录（与各模块一致）：market/、skills/、plugins/（复用
 * {@link PluginProperties} 插件目录）、mcp.yml。
 */
@Configuration(proxyBeanMethods = false)
public class MarketConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MarketManager marketManager(
            ObjectProvider<PluginManager> pluginManager,
            ObjectProvider<PluginProperties> pluginProperties,
            ObjectProvider<McpSettingsHolder> mcpHolder) {
        Path configRoot = CoreServicesConfiguration.configDir();
        PluginProperties props = pluginProperties.getIfAvailable();
        Path pluginsDir = props != null ? props.getPluginsDir() : configRoot.resolve("plugins");
        return new MarketManager(
                pluginsDir,
                configRoot.resolve("market"),
                configRoot.resolve("skills"),
                configRoot.resolve("market").resolve("state.json"),
                configRoot.resolve("mcp.yml"),
                pluginManager.getIfAvailable(),
                new McpSettingsStore(),
                mcpHolder.getIfAvailable());
    }
}
