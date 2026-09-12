package com.omniforge.tools.mcp;

import com.omniforge.core.agent.ToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * MCP 接入自动装配（需求 4.5 双轨制）：
 * {@link McpSettingsHolder}（启动时加载 mcp.yml，配置中心保存后热生效）、
 * {@link McpClientManager}（客户端生命周期/退避重连）与
 * {@link McpToolRegistrar}（MCP 工具注册进 ToolRegistry，Agent 可见）。
 */
@AutoConfiguration(afterName = "com.omniforge.core.agent.AgentAutoConfiguration")
@ConditionalOnClass({McpClient.class, ToolRegistry.class})
@EnableConfigurationProperties(McpProperties.class)
public class McpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public McpSettingsHolder mcpSettingsHolder(McpProperties properties) {
        return new McpSettingsHolder(new McpSettingsStore().load(properties.getConfigFile()));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "omniforge.mcp", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public McpClientManager mcpClientManager(McpSettingsHolder settingsHolder) {
        return new McpClientManager(settingsHolder);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "omniforge.mcp", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public McpToolRegistrar mcpToolRegistrar(McpClientManager manager,
                                             ObjectProvider<ToolRegistry> registries) {
        return new McpToolRegistrar(manager, registries.getIfAvailable(() -> null));
    }
}
