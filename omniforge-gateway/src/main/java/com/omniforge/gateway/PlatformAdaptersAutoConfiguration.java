package com.omniforge.gateway;

import com.omniforge.gateway.dingtalk.DingTalkAdapter;
import com.omniforge.gateway.dingtalk.DingTalkProperties;
import com.omniforge.gateway.dingtalk.DingTalkReplySender;
import com.omniforge.gateway.feishu.FeishuAdapter;
import com.omniforge.gateway.feishu.FeishuProperties;
import com.omniforge.gateway.feishu.FeishuReplySender;
import com.omniforge.gateway.wecom.WeComAdapter;
import com.omniforge.gateway.wecom.WeComProperties;
import com.omniforge.gateway.wecom.WeComReplySender;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 平台适配器与 Agent 触发接线装配（Phase 3 Step 4）：
 * 钉钉（纯 HTTP）/ 飞书（oapi-sdk）/ 企微（WxJava）适配器与回复通道 +
 * ImAgentMessageHandler（MessageHandler SPI 实现，被 ImMessageRouter 自动拾取）。
 */
@AutoConfiguration(afterName = "com.omniforge.gateway.ImGatewayAutoConfiguration")
@EnableConfigurationProperties({DingTalkProperties.class, FeishuProperties.class, WeComProperties.class})
public class PlatformAdaptersAutoConfiguration {

    // ---- 钉钉 ----
    @Bean
    @ConditionalOnMissingBean
    public DingTalkAdapter dingTalkAdapter(DingTalkProperties properties) {
        return new DingTalkAdapter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public DingTalkReplySender dingTalkReplySender(DingTalkProperties properties) {
        return new DingTalkReplySender(properties);
    }

    // ---- 飞书 ----
    @Bean
    @ConditionalOnMissingBean
    public FeishuAdapter feishuAdapter(FeishuProperties properties) {
        return new FeishuAdapter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public FeishuReplySender feishuReplySender(FeishuProperties properties) {
        return new FeishuReplySender(properties);
    }

    // ---- 企微 ----
    @Bean
    @ConditionalOnMissingBean
    public WeComAdapter weComAdapter(WeComProperties properties) {
        return new WeComAdapter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public WeComReplySender weComReplySender(WeComProperties properties) {
        return new WeComReplySender(properties);
    }

    // ---- Agent 触发接线（Step 4）：MessageHandler SPI 实现 ----
    @Bean
    @ConditionalOnMissingBean
    public ImAgentMessageHandler imAgentMessageHandler(
            com.omniforge.core.agent.AgentEngine agentEngine,
            com.omniforge.core.gateway.ModelGateway modelGateway,
            com.omniforge.core.agent.ToolRegistry toolRegistry,
            org.springframework.beans.factory.ObjectProvider<com.omniforge.core.debate.DebateEngine> debateEngines,
            org.springframework.beans.factory.ObjectProvider<com.omniforge.core.persistence.service.LicenseService> licenseServices,
            org.springframework.beans.factory.ObjectProvider<com.omniforge.core.context.ContextManager> contextManagers,
            org.springframework.beans.factory.ObjectProvider<com.omniforge.core.persistence.service.ChatSessionService> chatSessionServices,
            java.util.List<ImReplySender> replySenders) {
        return new ImAgentMessageHandler(agentEngine, modelGateway, toolRegistry,
                debateEngines.getIfAvailable(() -> null),
                licenseServices.getIfAvailable(() -> null),
                replySenders,
                contextManagers.getIfAvailable(() -> null),
                chatSessionServices.getIfAvailable(() -> null));
    }
}
