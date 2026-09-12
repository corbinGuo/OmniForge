package com.omniforge.app.safety;

import com.omniforge.common.spi.ToolApproval;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具确认通道装配（HITL，A1）：
 * 仅 GUI 模式（未设 omniforge.headless）注册 {@link ToolApproval} Bean；
 * Headless/MCP server 分支设置 omniforge.headless=true → 不装配，引擎走 Q3-A 无通道放行。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "omniforge.headless", havingValue = "false", matchIfMissing = true)
public class AgentSafetyConfiguration {

    @Bean
    @ConditionalOnMissingBean(ToolApproval.class)
    public ToolApproval toolApproval() {
        return new AgentSafetyGuiApproval();
    }
}
