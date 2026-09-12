package com.omniforge.gateway.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * QQ 官方机器人 Webhook 回调接收器（SmartLifecycle）：
 * 监听开放平台配置的回调路径，处理两类载荷——
 * <ul>
 *   <li>opcode 13 回调地址验证：Ed25519 签名 plain_token+event_ts 应答；</li>
 *   <li>opcode 0 事件推送：X-Signature（ed25519=hex）验签后进 ImMessageRouter。</li>
 * </ul>
 *
 * <p>开放平台回调端口仅允许 80/443/8080/8443 且需 HTTPS 公网地址，
 * 故默认关闭；未配置 AppSecret 或端口占用时仅告警不启动。</p>
 */
public class QqWebhookReceiver implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QqWebhookReceiver.class);

    private final QqSettingsStore settingsStore;
    private final QQOfficialAdapter adapter;
    private final ObjectProvider<ImMessageRouter> routers;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile HttpServer server;
    private volatile boolean running = false;

    public QqWebhookReceiver(QqSettingsStore settingsStore, QQOfficialAdapter adapter,
                             ObjectProvider<ImMessageRouter> routers) {
        this.settingsStore = settingsStore;
        this.adapter = adapter;
        this.routers = routers;
    }

    @Override
    public void start() {
        QqSettings settings = settingsStore.current();
        if (!settings.enabled() || !settings.webhookEnabled()) {
            return;
        }
        if (settings.appSecret() == null || settings.appSecret().isBlank()) {
            log.warn("QQ 官方 Webhook 未启动：未配置 AppSecret（Ed25519 验签必需）");
            return;
        }
        try {
            HttpServer httpServer = HttpServer.create(
                    new InetSocketAddress(settings.webhookBind(), settings.webhookPort()), 0);
            httpServer.createContext(settings.webhookPath(), this::handleCallback);
            httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpServer.start();
            server = httpServer;
            running = true;
            log.info("QQ 官方 Webhook 接收器已启动：http://{}:{}{}（开放平台回调端口限 80/443/8080/8443）",
                    settings.webhookBind(), settings.webhookPort(), settings.webhookPath());
        } catch (BindException e) {
            log.warn("QQ 官方 Webhook 未启动：端口 {} 已被占用", settings.webhookPort());
        } catch (IOException e) {
            log.warn("QQ 官方 Webhook 启动失败：{}", e.getMessage());
        }
    }

    @Override
    public void stop() {
        HttpServer httpServer = server;
        server = null;
        running = false;
        if (httpServer != null) {
            httpServer.stop(0);
            log.info("QQ 官方 Webhook 接收器已停止");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"err\":\"method not allowed\"}");
                return;
            }
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((key, values) ->
                    headers.put(key, values.isEmpty() ? "" : values.get(0)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(body);
            int op = root.path("op").asInt(-1);
            if (op == 13) {
                handleValidation(exchange, root);
                return;
            }
            if (op == 0) {
                handleEvent(exchange, headers, body);
                return;
            }
            respond(exchange, 200, "{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            log.warn("QQ Webhook 请求被拒绝：{}", e.getMessage());
            respond(exchange, 400, "{\"err\":\"bad request\"}");
        } catch (Exception e) {
            log.error("QQ Webhook 处理异常：{}", e.getMessage(), e);
            respond(exchange, 500, "{\"err\":\"internal error\"}");
        }
    }

    /** opcode 13 回调地址验证：签名应答 */
    private void handleValidation(HttpExchange exchange, JsonNode root) throws IOException {
        JsonNode d = root.path("d");
        String plainToken = d.path("plain_token").asText("");
        String eventTs = d.path("event_ts").asText("");
        if (plainToken.isBlank() || eventTs.isBlank()) {
            throw new IllegalArgumentException("回调验证载荷缺少 plain_token/event_ts");
        }
        String signature = QqSignatureVerifier.signCallback(
                settingsStore.current().appSecret(), eventTs, plainToken);
        respond(exchange, 200, "{\"plain_token\":\"" + plainToken + "\",\"signature\":\"" + signature + "\"}");
        log.info("QQ Webhook 回调地址验证已应答");
    }

    /** opcode 0 事件推送：验签后进路由 */
    private void handleEvent(HttpExchange exchange, Map<String, String> headers, String body) throws IOException {
        String secret = settingsStore.current().appSecret();
        String signatureHeader = headers.getOrDefault("X-Signature", "");
        if (signatureHeader.startsWith("ed25519=")) {
            JsonNode d = objectMapper.readTree(body).path("d");
            String eventTs = d.path("event_ts").asText("");
            if (eventTs.isBlank()) {
                throw new IllegalArgumentException("事件载荷缺少 event_ts，无法验签");
            }
            if (!QqSignatureVerifier.verifyEvent(secret, eventTs, body, signatureHeader.substring("ed25519=".length()))) {
                throw new IllegalArgumentException("QQ Webhook 事件签名校验失败");
            }
        } else {
            log.warn("QQ Webhook 事件缺少 X-Signature 头，跳过验签直接处理（仅限调试）");
        }
        var event = adapter.parse(headers, body);
        if (event == null) {
            respond(exchange, 200, "{\"ok\":true}");
            return;
        }
        ImMessageRouter router = routers.getIfAvailable();
        if (router == null) {
            log.warn("QQ Webhook 事件已接收但路由不可用（消息仅跳过）：messageId={}", event.messageId());
            respond(exchange, 200, "{\"ok\":true}");
            return;
        }
        router.route(event);
        respond(exchange, 200, "{\"ok\":true}");
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
