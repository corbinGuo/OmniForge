package com.omniforge.app.enterprise;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话管理器测试（内存 token/缓存语义，设计确认 #1/#2） */
class EnterpriseSessionManagerTest {

    @Test
    void 登录后拉取会话缓存且新建会话自动刷新() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicInteger sessionsCreated = new AtomicInteger();
        server.createContext("/api/auth/login", exchange ->
                respond(exchange, "{\"token\":\"t\",\"role\":\"member\",\"username\":\"u\"}"));
        server.createContext("/api/sessions", exchange -> {
            int count = sessionsCreated.get();
            String list = count == 0 ? "[]" : "[{\"id\":\"s-new\",\"name\":\"你好…\","
                    + "\"createdAt\":\"2026-08-29T10:00:00Z\"}]";
            respond(exchange, list);
        });
        server.createContext("/api/agent/chat", exchange -> {
            sessionsCreated.incrementAndGet();
            respond(exchange, "{\"text\":\"回复\",\"stopReason\":\"COMPLETED\","
                    + "\"modelAlias\":\"m\",\"sessionId\":\"s-new\"}");
        });
        server.start();
        try {
            EnterpriseSessionManager manager = new EnterpriseSessionManager(
                    "http://localhost:" + server.getAddress().getPort());
            assertTrue(!manager.isLoggedIn());
            manager.login("u", "pw");
            assertTrue(manager.isLoggedIn());
            assertTrue(manager.sessions().isEmpty()); // 初始为空缓存

            EnterpriseApiClient.ChatResult result = manager.chat(null, "你好");
            assertEquals("s-new", result.sessionId());
            // 新建会话后缓存自动刷新（设计确认 #2）
            assertEquals(1, manager.sessions().size());
            assertEquals("s-new", manager.sessions().get(0).id());

            manager.logout();
            assertTrue(!manager.isLoggedIn());
            assertTrue(manager.sessions().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 重命名与删除同步服务端并更新缓存() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", exchange ->
                respond(exchange, "{\"token\":\"t\",\"role\":\"member\",\"username\":\"u\"}"));
        server.createContext("/api/sessions", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (method.equals("GET") && path.equals("/api/sessions")) {
                respond(exchange, "[{\"id\":\"s-1\",\"name\":\"旧名\","
                        + "\"createdAt\":\"2026-08-29T10:00:00Z\"}]");
            } else if (method.equals("PATCH")) {
                respond(exchange, "{\"id\":\"s-1\",\"name\":\"新名\","
                        + "\"createdAt\":\"2026-08-29T10:00:00Z\"}");
            } else if (method.equals("DELETE")) {
                respond(exchange, "");
            } else {
                respond(exchange, "[]");
            }
        });
        server.start();
        try {
            EnterpriseSessionManager manager = new EnterpriseSessionManager(
                    "http://localhost:" + server.getAddress().getPort());
            manager.login("u", "pw");
            assertEquals("旧名", manager.sessions().get(0).name());

            manager.renameSession("s-1", "新名");
            assertEquals("新名", manager.sessions().get(0).name()); // 缓存同步

            manager.deleteSession("s-1");
            assertTrue(manager.sessions().isEmpty()); // 缓存同步删除
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
