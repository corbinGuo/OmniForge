package com.omniforge.tools.mcp;

import com.omniforge.common.retry.ExponentialBackoff;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * MCP 客户端生命周期管理（需求 4.5）：
 * 每个启用的服务器维护一个 {@link McpSyncClient}（stdio 子进程 / streamable HTTP 远程服务）。
 *
 * <p>韧性设计（与 0 模型韧性一致）：
 * <ul>
 *   <li>启动即异步连接，失败仅告警不阻断应用启动（0 服务器可用 = 0 个 MCP 工具）；</li>
 *   <li>连接失败/断开后经 {@link ExponentialBackoff} 指数退避重连
 *       （基础 30s、封顶 600s，成功即复位）；</li>
 *   <li>配置热生效：{@link McpSettingsHolder} 变化后 apply() 重建连接；</li>
 *   <li>服务器端工具列表变化（toolsChangeConsumer）→ 刷新适配器并通知注册表；</li>
 *   <li>stop() 优雅关闭全部客户端与 stdio 子进程。</li>
 * </ul>
 */
public class McpClientManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);
    /** 重连退避基础间隔（毫秒） */
    static final long BASE_BACKOFF_MILLIS = 30_000;
    /** 重连退避封顶（毫秒，10 分钟） */
    static final long MAX_BACKOFF_MILLIS = 600_000;
    /** 单次 MCP 调用超时 */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** 客户端构建器（测试注入用：假客户端/失败注入） */
    @FunctionalInterface
    interface McpClientFactory {
        McpSyncClient create(McpServerConfig config) throws Exception;
    }

    private final McpSettingsHolder settingsHolder;
    private final McpClientFactory clientFactory;
    private final long baseBackoffMillis;
    private final ConcurrentHashMap<String, ServerRuntime> runtimes = new ConcurrentHashMap<>();
    private volatile Consumer<List<McpToolAdapter>> changeListener = tools -> {
    };
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running;

    public McpClientManager(McpSettingsHolder settingsHolder) {
        this(settingsHolder, McpClientManager::createClient, BASE_BACKOFF_MILLIS);
    }

    /** 测试可见：注入客户端构建器与退避基础间隔 */
    McpClientManager(McpSettingsHolder settingsHolder, McpClientFactory clientFactory,
                     long baseBackoffMillis) {
        this.settingsHolder = settingsHolder;
        this.clientFactory = clientFactory;
        this.baseBackoffMillis = baseBackoffMillis;
    }

    /** 工具列表变化回调（McpToolRegistrar 订阅） */
    public void setChangeListener(Consumer<List<McpToolAdapter>> listener) {
        this.changeListener = listener == null ? tools -> {
        } : listener;
    }

    /** 当前全部已连接服务器的工具适配器快照 */
    public List<McpToolAdapter> tools() {
        List<McpToolAdapter> all = new ArrayList<>();
        runtimes.values().forEach(runtime -> all.addAll(runtime.adapters()));
        return List.copyOf(all);
    }

    @Override
    public void start() {
        if (!running) {
            running = true;
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "omniforge-mcp-reconnect");
                thread.setDaemon(true);
                return thread;
            });
        }
        apply(settingsHolder.current());
    }

    /** 应用新设置（配置中心保存后热生效）：停用/变更的服务器断开，新增/变更的重新连接 */
    public void apply(McpSettings settings) {
        if (!running) {
            return;
        }
        Set<String> wanted = settings.servers().stream()
                .filter(McpServerConfig::enabled)
                .map(McpServerConfig::name)
                .collect(Collectors.toSet());
        runtimes.keySet().stream()
                .filter(name -> !wanted.contains(name))
                .forEach(this::stopServer);
        for (McpServerConfig config : settings.servers()) {
            if (!config.enabled()) {
                continue;
            }
            ServerRuntime existing = runtimes.get(config.name());
            if (existing == null || !existing.config().equals(config)) {
                if (existing != null) {
                    stopServer(config.name());
                }
                runtimes.put(config.name(), new ServerRuntime(config,
                        new ExponentialBackoff(baseBackoffMillis, MAX_BACKOFF_MILLIS)));
                connectAsync(config.name());
            }
        }
        notifyChange();
    }

    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdownNow();
        }
        new ArrayList<>(runtimes.keySet()).forEach(this::stopServer);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---------- 连接管理 ----------

    private void connectAsync(String name) {
        Thread.ofVirtual().name("omniforge-mcp-" + name, 0).start(() -> connectAttempt(name));
    }

    private void connectAttempt(String name) {
        ServerRuntime runtime = runtimes.get(name);
        if (runtime == null || !running) {
            return;
        }
        try {
            McpSyncClient client = clientFactory.create(runtime.config());
            List<McpToolAdapter> adapters = client.listTools().tools().stream()
                    .map(tool -> new McpToolAdapter(name, client, tool))
                    .toList();
            runtime.connected(client, adapters);
            runtime.backoff().onSuccess();
            log.info("MCP 服务器 [{}] 已连接：{} 个工具 [{}]", name, adapters.size(),
                    adapters.stream().map(a -> a.spec().name()).collect(Collectors.joining(", ")));
            notifyChange();
        } catch (Exception e) {
            log.warn("MCP 服务器 [{}] 连接失败，将退避重试：{}", name, e.getMessage(), e);
            long delay = runtime.backoff().onFailure();
            scheduleReconnect(name, delay);
            notifyChange();
        }
    }

    private void scheduleReconnect(String name, long delayMillis) {
        ScheduledExecutorService current = scheduler;
        if (current == null || !running) {
            return;
        }
        try {
            current.schedule(() -> connectAttempt(name), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("重连调度失败：{}", e.getMessage());
        }
    }

    private void stopServer(String name) {
        ServerRuntime runtime = runtimes.remove(name);
        if (runtime == null) {
            return;
        }
        McpSyncClient client = runtime.client();
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (RuntimeException e) {
                log.debug("MCP 服务器 [{}] 关闭异常：{}", name, e.getMessage());
            }
        }
        log.info("MCP 服务器 [{}] 已断开", name);
    }

    private void notifyChange() {
        try {
            changeListener.accept(tools());
        } catch (RuntimeException e) {
            log.warn("MCP 工具变化通知失败：{}", e.getMessage());
        }
    }

    // ---------- 客户端构建 ----------

    /** 按配置构建 MCP 同步客户端（stdio 子进程 / streamable HTTP） */
    private static McpSyncClient createClient(McpServerConfig config) throws Exception {
        McpJsonMapper mapper = McpJsonMapper.getDefault();
        McpClientTransport transport = switch (config.transport()) {
            case McpServerConfig.TRANSPORT_STDIO -> {
                StdioClientTransport stdio = new StdioClientTransport(stdioParameters(config), mapper);
                // 子进程 stderr 走日志（诊断启动失败原因），不混入 stdio 协议流
                stdio.setStdErrorHandler(line -> log.warn("MCP 子进程 [{}] stderr：{}", config.name(), line));
                yield stdio;
            }
            case McpServerConfig.TRANSPORT_HTTP -> HttpClientStreamableHttpTransport
                    .builder(config.url()).build();
            default -> throw new IllegalArgumentException("不支持的传输方式: " + config.transport());
        };
        return McpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .build();
    }

    /**
     * 解析 stdio 启动参数。Windows 平台上 MCP 常用裸命令（npx/uvx 等）实为
     * {@code .cmd/.bat} shim，Java CreateProcess 不能直接执行（报 error=2
     * 系统找不到文件），需包一层 {@code cmd.exe /c} 才能找到并运行。
     * 带路径或已是 .exe/.cmd 的显式命令原样透传；非 Windows 不受影响。
     */
    static ServerParameters stdioParameters(McpServerConfig config) {
        String command = config.command();
        if (isWindowsBareCommand(command)) {
            List<String> wrapped = new ArrayList<>(config.args().size() + 2);
            wrapped.add("/c");
            wrapped.add(command);
            wrapped.addAll(config.args());
            return ServerParameters.builder("cmd.exe").args(wrapped).build();
        }
        return ServerParameters.builder(command).args(config.args()).build();
    }

    private static boolean isWindowsBareCommand(String command) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")
                || command == null || command.isBlank()) {
            return false;
        }
        String lower = command.toLowerCase();
        return !lower.contains("\\") && !lower.contains("/")
                && !lower.endsWith(".exe") && !lower.endsWith(".cmd") && !lower.endsWith(".bat");
    }

    /** 单服务器运行态：配置 + 退避 + 当前客户端与适配器 */
    private static final class ServerRuntime {

        private final McpServerConfig config;
        private final ExponentialBackoff backoff;
        private volatile McpSyncClient client;
        private volatile List<McpToolAdapter> adapters = List.of();

        ServerRuntime(McpServerConfig config, ExponentialBackoff backoff) {
            this.config = config;
            this.backoff = backoff;
        }

        McpServerConfig config() {
            return config;
        }

        ExponentialBackoff backoff() {
            return backoff;
        }

        McpSyncClient client() {
            return client;
        }

        List<McpToolAdapter> adapters() {
            return adapters;
        }

        void connected(McpSyncClient newClient, List<McpToolAdapter> newAdapters) {
            this.client = newClient;
            this.adapters = newAdapters;
        }
    }
}
