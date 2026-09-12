package com.omniforge.app;

import com.omniforge.core.agent.AgentEngine;
import com.omniforge.core.agent.ToolRegistry;
import com.omniforge.core.gateway.ModelGateway;
import com.omniforge.knowledge.KnowledgeService;
import com.omniforge.tools.ToolsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配冒烟测试：与启动器相同的装配路径（全模块 autoconfiguration），
 * 使用 :memory: 数据库 + 临时工作区，验证核心 Bean 与工具聚合可用。
 */
class AppAssemblyTest {

    @Test
    void 全模块装配冒烟() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder()
                .sources(OmniForgeLauncher.EmptyConfiguration.class)
                .web(WebApplicationType.NONE)
                .headless(true)
                .properties(
                        "omniforge.persistence.database-file=:memory:",
                        "omniforge.tools.workspace-root=" + java.nio.file.Path.of(
                                System.getProperty("java.io.tmpdir"), "omniforge-test-workspace").toAbsolutePath(),
                        // 插件/审计/向量库指向临时目录，避免装配冒烟污染真实配置目录
                        "omniforge.plugin.plugins-dir=" + java.nio.file.Path.of(
                                System.getProperty("java.io.tmpdir"), "omniforge-test-plugins").toAbsolutePath(),
                        "omniforge.audit.directory=" + java.nio.file.Path.of(
                                System.getProperty("java.io.tmpdir"), "omniforge-test-audit").toAbsolutePath(),
                        "omniforge.knowledge.vector-db-file=" + java.nio.file.Path.of(
                                System.getProperty("java.io.tmpdir"), "omniforge-test-vectors.db").toAbsolutePath())
                .run()) {

            // 核心 Bean 齐备
            assertThat(context.getBean(ModelGateway.class)).isNotNull();
            assertThat(context.getBean(AgentEngine.class)).isNotNull();
            assertThat(context.getBean(ToolRegistry.class)).isNotNull();
            assertThat(context.getBean(DataSource.class)).isNotNull();
            assertThat(context.getBean(KnowledgeService.class)).isNotNull();
            assertThat(context.getBean(ToolsProperties.class)).isNotNull();
            assertThat(context.getBean(com.omniforge.core.context.ContextManager.class)).isNotNull();
            assertThat(context.getBean(com.omniforge.tools.mcp.McpClientManager.class)).isNotNull();
            assertThat(context.getBean(com.omniforge.tools.mcp.McpToolRegistrar.class)).isNotNull();

            // 工具聚合：内置工具 + 知识库工具经 ServiceLoader 被发现
            ToolRegistry registry = context.getBean(ToolRegistry.class);
            assertThat(registry.find("web_search")).isPresent();
            assertThat(registry.find("file_read_write")).isPresent();
            assertThat(registry.find("shell_executor")).isPresent();
            assertThat(registry.find("knowledge_search")).isPresent();
        }
    }
}
