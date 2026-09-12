package com.omniforge.core.audit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 审计日志自动装配（C-tier 批次 4-1）。
 * omniforge.audit.enabled=false 可关闭（Bean 不存在时各挂接点自动跳过）。
 */
@AutoConfiguration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "omniforge.audit", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AuditLogService auditLogService(AuditProperties properties) {
        return new AuditLogService(properties);
    }
}
