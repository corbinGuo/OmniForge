package com.omniforge.core.gateway;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * 模型网关自动装配。
 *
 * <p>桌面端（omniforge-app）以编程方式启动 Spring 上下文即可获得完整网关能力；
 * 企业版服务端（omniforge-enterprise-server）为 Spring Boot 应用，同样自动生效。
 * 仅当类路径上存在 Spring AI ChatModel 时装配（即已引入 Spring AI 相关 starter）。</p>
 */
@AutoConfiguration
@ConditionalOnClass(ChatModel.class)
@EnableConfigurationProperties(GatewayProperties.class)
public class ModelGatewayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ModelConfigLoader modelConfigLoader() {
        return new ModelConfigLoader();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatModelProvider chatModelProvider() {
        return new SpringAiChatModelProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiKeyResolver apiKeyResolver(GatewayProperties properties) {
        return new DefaultApiKeyResolver(keysDirectory(properties));
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelRouter modelRouter() {
        return new AliasModelRouter();
    }

    /** 网关核心 Bean；构造即完成首次 models.yml 加载（缺失时自动生成默认模板） */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ModelGateway.class)
    public DefaultModelGateway modelGateway(GatewayProperties properties, ModelConfigLoader configLoader,
                                            ChatModelProvider chatModelProvider,
                                            ApiKeyResolver apiKeyResolver, ModelRouter modelRouter) {
        return new DefaultModelGateway(properties.getConfigFile(), configLoader,
                chatModelProvider, apiKeyResolver, modelRouter);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "omniforge.gateway", name = "watch-enabled",
            havingValue = "true", matchIfMissing = true)
    public ModelConfigWatcher modelConfigWatcher(GatewayProperties properties, DefaultModelGateway modelGateway) {
        return new ModelConfigWatcher(properties.getConfigFile(), modelGateway,
                properties.getWatchDebounceMillis());
    }

    private static Path keysDirectory(GatewayProperties properties) {
        Path parent = properties.getConfigFile().toAbsolutePath().getParent();
        return (parent != null ? parent : Path.of(".")).resolve("keys");
    }
}
