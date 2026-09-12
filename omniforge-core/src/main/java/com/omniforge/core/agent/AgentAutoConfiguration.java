package com.omniforge.core.agent;

import com.omniforge.core.debate.DebateEngine;
import com.omniforge.core.debate.ReactAgentFactory;
import com.omniforge.core.debate.SaaStreamingDebateRoundExecutor;
import com.omniforge.core.gateway.ModelGateway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Agent 引擎自动装配：提供工具注册表、默认 ReAct 引擎与辩论引擎（Phase 2）。
 * 依赖模型网关（{@link ModelGatewayAutoConfiguration} 已装配 ModelGateway）。
 */
@AutoConfiguration(afterName = {
        "com.omniforge.core.gateway.ModelGatewayAutoConfiguration",
        "com.omniforge.knowledge.KnowledgeAutoConfiguration"})
@ConditionalOnClass({ChatModel.class, ToolCallback.class})
public class AgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(
            org.springframework.beans.factory.ObjectProvider<com.omniforge.common.spi.ToolProvider> springProviders) {
        // Spring 管理的提供者优先注册（共享存储的 knowledge_search 等），
        // ServiceLoader 提供者兜底追加——重名时先注册者胜（内置/Spring 实例优先）
        return DefaultToolRegistry.merging(springProviders.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean(AgentEngine.class)
    public ReactAgentLoop agentEngine(ModelGateway modelGateway,
                                      org.springframework.beans.factory.ObjectProvider<com.omniforge.core.audit.AuditLogService> auditService,
                                      org.springframework.beans.factory.ObjectProvider<SystemInstructionContributor> contributors,
                                      org.springframework.beans.factory.ObjectProvider<com.omniforge.common.spi.ToolApproval> approvals) {
        // 审计服务未装配（omniforge.audit.enabled=false）时传 null，审计旁路关闭；
        // 系统提示贡献者（P1-3 Agent Skills）未装配时为空列表，消息组装与既有行为一致；
        // 工具确认通道（HITL，A1）未装配（Headless/企业）时传 null，自动放行 + WARN（Q3-A）
        return new ReactAgentLoop(modelGateway, new com.fasterxml.jackson.databind.ObjectMapper(),
                auditService.getIfAvailable(), contributors.orderedStream().toList(),
                approvals.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactAgentFactory reactAgentFactory(ModelGateway modelGateway) {
        return new ReactAgentFactory(modelGateway);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.omniforge.core.debate.JudgeEngine judgeEngine(ModelGateway modelGateway) {
        // 有裁判模式（2.4）：裁判模型复用已加载模型列表（经 judgeAlias 指定）
        return new com.omniforge.core.debate.GatewayJudgeEngine(modelGateway);
    }

    @Bean
    @ConditionalOnMissingBean
    public DebateEngine debateEngine(ReactAgentFactory reactAgentFactory,
                                     org.springframework.beans.factory.ObjectProvider<com.omniforge.core.debate.ConsensusDetector> detectors,
                                     org.springframework.beans.factory.ObjectProvider<com.omniforge.core.debate.JudgeEngine> judges) {
        // 默认执行器：逐模型并行、逐 token 流式；
        // 共识检测器由装配层注入（如 omniforge-app 的 EmbeddingConsensusDetector），未注入则关闭
        return new DebateEngine(new SaaStreamingDebateRoundExecutor(reactAgentFactory),
                detectors.getIfAvailable(() -> null),
                judges.getIfAvailable(() -> null));
    }
}
