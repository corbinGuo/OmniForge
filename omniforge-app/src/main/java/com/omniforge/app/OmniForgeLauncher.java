package com.omniforge.app;

import com.omniforge.ui.AppContextHolder;
import com.omniforge.ui.OmniForgeApplication;
import javafx.application.Application;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * GUI 启动器（Phase 1 P0 唯一入口；Headless 入口与 jpackage 在 Phase 3）。
 *
 * <p>启动顺序：
 * <ol>
 *   <li>以 SpringApplicationBuilder 装配全模块 autoconfiguration（classpath 的
 *       AutoConfiguration.imports 自动生效：网关/Agent/持久化/工具/知识库）；</li>
 *   <li>上下文注入 {@link AppContextHolder}；</li>
 *   <li>启动 JavaFX Application（OmniForgeApplication 从持有器取 Bean）。</li>
 * </ol>
 *
 * <p>运行方式：{@code mvn javafx:run}（omniforge-app 模块）。</p>
 */
public final class OmniForgeLauncher {

    private OmniForgeLauncher() {
    }

    public static void main(String[] args) {
        // 尽早放行受限头 Connection（企业 API 客户端每请求即用即关，
        // 防止 java.net.http keep-alive 池在突发下无界堆积连接拖垮服务端）
        if (System.getProperty("jdk.httpclient.allowRestrictedHeaders") == null) {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");
        }
        // Phase 4 安全底线：Linux root 运行警告（需求 9.2）
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")
                && "root".equals(System.getProperty("user.name"))) {
            org.slf4j.LoggerFactory.getLogger(OmniForgeLauncher.class)
                    .warn("检测到以 root 用户运行（不推荐）：请使用普通用户 + systemd 服务部署");
        }
        java.util.List<String> arguments = java.util.List.of(args);
        if (arguments.contains("--headless")) {
            runHeadless(arguments.stream()
                    .filter(a -> !"--headless".equals(a))
                    .toArray(String[]::new));
            return;
        }
        if (arguments.contains("--mcp-server")) {
            runMcpServer(args);
            return;
        }
        if (arguments.contains("--mode=enterprise")) {
            // 企业版桌面客户端（C-tier 批次 4-2）：装配企业版会话管理，走 GUI 装配路径
            System.setProperty("omniforge.enterprise.enabled", "true");
        }
        // 日志目录供 Log4j2（log4j2.xml 读取 ${sys:omniforge.log.dir}）
        System.setProperty("omniforge.log.dir", defaultConfigDir().resolve("logs").toString());
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder()
                .sources(EmptyConfiguration.class)
                .web(WebApplicationType.NONE)
                .headless(false) // JavaFX 需要 AWT 事件循环
                .initializers(new com.omniforge.app.retention.BackupInitializer())
                .run(args)) {
            AppContextHolder.set(context);
            Application.launch(OmniForgeApplication.class, args);
        }
    }

    /**
     * Headless 启动分支（Phase 3 Step 5）：跳过 JavaFX，仅装配
     * Spring 容器 + 消息网关 + Agent/辩论引擎 + 持久化 + 健康检查服务。
     * 进程由 HttpServer 的非守护线程维持存活。
     */
    private static void runHeadless(String[] args) {
        System.setProperty("omniforge.headless", "true");
        System.setProperty("omniforge.log.dir", defaultConfigDir().resolve("logs").toString());
        var context = new SpringApplicationBuilder()
                .sources(EmptyConfiguration.class, com.omniforge.app.headless.HeadlessConfiguration.class)
                .web(WebApplicationType.NONE)
                .headless(true)
                .initializers(new com.omniforge.app.retention.BackupInitializer())
                .run(args);
        // Phase 4：EULA 强制（Headless 场景经环境变量显式接受）
        com.omniforge.core.eula.EulaService eulaService =
                context.getBean(com.omniforge.core.eula.EulaService.class);
        if (!eulaService.isAccepted() && !"true".equalsIgnoreCase(System.getenv("OMNI_ACCEPT_EULA"))) {
            org.slf4j.LoggerFactory.getLogger(OmniForgeLauncher.class)
                    .error("EULA 未接受：请先在 GUI 模式签署，或设置环境变量 OMNI_ACCEPT_EULA=true 表示接受");
            context.close();
            System.exit(1);
        }
        int port = context.getBean(com.omniforge.app.headless.HeadlessHttpServer.class).getPort();
        org.slf4j.LoggerFactory.getLogger(OmniForgeLauncher.class)
                .info("Headless 模式已启动：http://localhost:{}/health", port);
    }

    /**
     * MCP server 分支（P1-4 反向）：把内置工具以 stdio MCP 暴露给外部客户端。
     * 与 headless 同装配但<b>不启动</b>健康检查 HTTP；控制台日志改走 stderr
     * （log4j2.xml Console target 由 omniforge.console.target 控制），
     * 关闭 Spring banner，保证 stdout 只含 MCP 协议帧。EOF 退出。
     */
    private static void runMcpServer(String[] args) {
        System.setProperty("omniforge.console.target", "SYSTEM_ERR");
        System.setProperty("omniforge.headless", "true");
        // 本机对外 MCP 服务端不建立内层 MCP 客户端连接（设计 §1#8：无 mcp_* 聚合工具需要）
        System.setProperty("omniforge.mcp.enabled", "false");
        System.setProperty("omniforge.log.dir", defaultConfigDir().resolve("logs").toString());
        ConfigurableApplicationContext context = new SpringApplicationBuilder()
                .sources(EmptyConfiguration.class)
                .web(WebApplicationType.NONE)
                .headless(true)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .initializers(new com.omniforge.app.retention.BackupInitializer())
                .run(args);
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OmniForgeLauncher.class);
        try {
            com.omniforge.core.eula.EulaService eulaService =
                    context.getBean(com.omniforge.core.eula.EulaService.class);
            if (!eulaService.isAccepted() && !"true".equalsIgnoreCase(System.getenv("OMNI_ACCEPT_EULA"))) {
                log.error("EULA 未接受：请先在 GUI 模式签署，或设置 OMNI_ACCEPT_EULA=true");
                context.close();
                System.exit(1);
            }
            com.omniforge.core.agent.ToolRegistry registry =
                    context.getBean(com.omniforge.core.agent.ToolRegistry.class);
            com.omniforge.tools.ToolsSettingsHolder tools = context
                    .getBeanProvider(com.omniforge.tools.ToolsSettingsHolder.class).getIfAvailable();
            com.omniforge.app.mcpserver.OmniForgeMcpServer.run(registry, tools, context);
            System.exit(0);
        } catch (Throwable e) {
            log.error("OmniForge MCP server 启动失败", e);
            context.close();
            System.exit(2);
        }
    }

    /** 最小配置类：@EnableAutoConfiguration 触发 AutoConfiguration.imports 全量装配 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({ConsensusConfiguration.class, CoreServicesConfiguration.class,
            com.omniforge.app.enterprise.EnterpriseConfiguration.class,
            com.omniforge.app.market.MarketConfiguration.class,
            com.omniforge.app.safety.AgentSafetyConfiguration.class})
    static class EmptyConfiguration {
    }

    /** 默认配置目录：Linux ~/.omniforge；Windows %APPDATA%\OmniForge（与其他模块一致） */
    private static java.nio.file.Path defaultConfigDir() {
        String userHome = System.getProperty("user.home", ".");
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            java.nio.file.Path base = (appData != null && !appData.isBlank())
                    ? java.nio.file.Path.of(appData) : java.nio.file.Path.of(userHome);
            return base.resolve("OmniForge");
        }
        return java.nio.file.Path.of(userHome, ".omniforge");
    }
}
