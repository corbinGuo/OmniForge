package com.omniforge.gateway;

import com.omniforge.core.persistence.repository.ImMessageRepository;
import com.omniforge.core.persistence.service.ImMessageDedupService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * IM 网关自动装配（Phase 3 Step 2 骨架）：
 * 白名单、消息路由（幂等→白名单→日志→异步队列）。
 * MessageHandler 由 Step 3/4 装配注入（无处理器时路由仅记录日志与状态流转）。
 */
@AutoConfiguration(afterName = "com.omniforge.core.persistence.PersistenceAutoConfiguration")
@ConditionalOnClass(ImMessageDedupService.class)
@EnableConfigurationProperties(ImGatewayProperties.class)
public class ImGatewayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ImWhitelist imWhitelist(ImGatewayProperties properties) {
        return new ImWhitelist(properties.isWhitelistEnabled(), properties.getWhitelistSenders());
    }

    @Bean
    @ConditionalOnMissingBean
    public ImMessageRouter imMessageRouter(ImMessageDedupService dedupService, ImWhitelist whitelist,
                                           ImMessageRepository messageLog,
                                           ObjectProvider<MessageHandler> handlers) {
        return new ImMessageRouter(dedupService, whitelist, messageLog,
                handlers.getIfAvailable(() -> null));
    }
}
