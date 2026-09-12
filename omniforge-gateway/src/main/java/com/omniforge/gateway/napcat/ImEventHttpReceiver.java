package com.omniforge.gateway.napcat;

import com.omniforge.gateway.ImMessageRouter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * NapCat 事件接收端点（SmartLifecycle，GUI/Headless 均可用，仅 napcat.enabled 门控）：
 * 监听 {@code POST /onebot/event} 接收 OneBot 11 HTTP 事件上报，
 * 解析后进 ImMessageRouter（幂等/白名单/日志/队列全复用）。
 *
 * <p>优雅降级：端口被占用或未配置 token 时仅告警、不启动，不影响主流程。</p>
 */
public class ImEventHttpReceiver implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ImEventHttpReceiver.class);

    private final NapCatProperties properties;
    private final NapCatAdapter adapter;
    private final ObjectProvider<ImMessageRouter> routers;

    private volatile HttpServer server;
    private volatile boolean running = false;

    public ImEventHttpReceiver(NapCatProperties properties, NapCatAdapter adapter,
                               ObjectProvider<ImMessageRouter> routers) {
        this.properties = properties;
        this.adapter = adapter;
        this.routers = routers;
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            log.error("NapCat 事件接收器未启动：未配置 token（omniforge.im.napcat.token），拒绝裸奔");
            return;
        }
        try {
            HttpServer httpServer = HttpServer.create(
                    new InetSocketAddress(properties.getEventBind(), properties.getEventPort()), 0);
            httpServer.createContext("/onebot/event", this::handleEvent);
            httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpServer.start();
            server = httpServer;
            running = true;
            log.info("NapCat 事件接收器已启动：http://{}:{}/onebot/event", properties.getEventBind(),
                    properties.getEventPort());
        } catch (BindException e) {
            // 端口冲突优雅降级（补充建议 #2）：仅告警，应用继续运行
            log.warn("NapCat 事件接收器未启动：端口 {} 已被占用，可调整 omniforge.im.napcat.event-port",
                    properties.getEventPort());
        } catch (IOException e) {
            log.warn("NapCat 事件接收器启动失败：{}", e.getMessage());
        }
    }

    @Override
    public void stop() {
        HttpServer httpServer = server;
        server = null;
        running = false;
        if (httpServer != null) {
            httpServer.stop(0);
            log.info("NapCat 事件接收器已停止");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void handleEvent(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "仅支持 POST");
                return;
            }
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((key, values) ->
                    headers.put(key, values.isEmpty() ? "" : values.get(0)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var message = adapter.parse(headers, body);
            if (message == null) {
                respond(exchange, 200, "ignored"); // 心跳/元事件
                return;
            }
            ImMessageRouter router = routers.getIfAvailable();
            if (router == null) {
                log.warn("NapCat 事件已接收但路由不可用（消息仅跳过）：messageId={}", message.messageId());
                respond(exchange, 200, "ok");
                return;
            }
            router.route(message);
            respond(exchange, 200, "ok");
        } catch (IllegalArgumentException e) {
            log.warn("NapCat 事件被拒绝：{}", e.getMessage());
            respond(exchange, 400, "bad request");
        } catch (Exception e) {
            log.error("NapCat 事件处理异常：{}", e.getMessage(), e);
            respond(exchange, 500, "internal error");
        }
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
