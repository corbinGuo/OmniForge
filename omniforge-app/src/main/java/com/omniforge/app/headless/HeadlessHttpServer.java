package com.omniforge.app.headless;

import com.omniforge.core.gateway.ModelGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless 健康检查/指标服务（JDK 内置 HttpServer，零新依赖）：
 * <ul>
 *   <li>{@code GET /health} —— JSON 状态（模型数 / 数据库连通 / 引擎装配）；</li>
 *   <li>{@code GET /metrics} —— Prometheus 文本格式（模型数/调用次数/成本/JVM 内存）。</li>
 * </ul>
 * HttpServer 使用非守护平台线程，维持 Headless 进程存活。
 */
public class HeadlessHttpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HeadlessHttpServer.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final int port;
    private final boolean metricsEnabled;
    private final ModelGateway modelGateway;
    private final DataSource dataSource;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile HttpServer server;

    public HeadlessHttpServer(HeadlessProperties properties, ModelGateway modelGateway, DataSource dataSource) {
        this.port = properties.getPort();
        this.metricsEnabled = properties.isMetricsEnabled();
        this.modelGateway = modelGateway;
        this.dataSource = dataSource;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/health", this::handleHealth);
            if (metricsEnabled) {
                server.createContext("/metrics", this::handleMetrics);
            }
            server.setExecutor(com.omniforge.core.scheduler.OmniForgeExecutors
                    .newVirtualThreadPerTaskExecutor("omniforge-headless"));
            server.start();
            log.info("健康检查已启动：http://localhost:{}/health", port);
        } catch (IOException e) {
            running.set(false);
            throw new IllegalStateException("健康检查服务启动失败（端口 " + port + "）", e);
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        int models = modelGateway.availableModels().size();
        String db = databaseStatus();
        String status = "UP".equals(db) ? "UP" : "DEGRADED";
        String json = healthJson(status, models, db);
        respond(exchange, 200, "application/json", json);
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        String text = metricsText(
                modelGateway.availableModels().size(),
                modelGateway.usage().totalCalls(),
                modelGateway.usage().totalEstimatedCostUsd());
        respond(exchange, 200, "text/plain; version=0.0.4", text);
    }

    private String databaseStatus() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT 1");
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** /health JSON 构建（静态便于测试） */
    static String healthJson(String status, int models, String db) {
        return "{\"status\":\"" + status + "\",\"models\":" + models
                + ",\"db\":\"" + db + "\",\"timestamp\":\""
                + LocalDateTime.now().format(TIME_FORMAT) + "\"}";
    }

    /** /metrics Prometheus 文本构建（静态便于测试） */
    static String metricsText(int models, long totalCalls, double costUsd) {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        return "# HELP omniforge_models_available 可用模型数量\n"
                + "# TYPE omniforge_models_available gauge\n"
                + "omniforge_models_available " + models + "\n"
                + "# TYPE omniforge_total_calls counter\n"
                + "omniforge_total_calls " + totalCalls + "\n"
                + "# TYPE omniforge_total_cost_usd counter\n"
                + "omniforge_total_cost_usd " + costUsd + "\n"
                + "# TYPE jvm_memory_used_bytes gauge\n"
                + "jvm_memory_used_bytes " + usedMemory + "\n"
                + "# TYPE jvm_memory_max_bytes gauge\n"
                + "jvm_memory_max_bytes " + maxMemory + "\n";
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false) && server != null) {
            server.stop(0);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** 配置端口（启动器日志用） */
    public int getPort() {
        return port;
    }
}
