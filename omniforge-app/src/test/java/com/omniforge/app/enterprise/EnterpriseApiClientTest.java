package com.omniforge.app.enterprise;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 企业版 API 客户端测试（JDK HttpServer 桩服务，零新依赖）：
 * token 自动注入（设计确认 #4）+ 会话端点往返。 */
class EnterpriseApiClientTest {

    @Test
    void 登录后token自动注入后续请求() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", exchange ->
                respond(exchange, "{\"token\":\"test-jwt\",\"role\":\"member\",\"username\":\"u\"}"));
        server.createContext("/api/agent/chat", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !authorization.equals("Bearer test-jwt")) {
                byte[] error = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, error.length);
                exchange.getResponseBody().write(error);
                exchange.close();
                return;
            }
            respond(exchange, "{\"text\":\"服务端回复\",\"stopReason\":\"COMPLETED\","
                    + "\"modelAlias\":\"m\",\"sessionId\":\"s-1\"}");
        });
        server.start();
        try {
            EnterpriseApiClient client = new EnterpriseApiClient(
                    "http://localhost:" + server.getAddress().getPort());
            EnterpriseApiClient.LoginResult login = client.login("user", "pw");
            assertEquals("test-jwt", login.token());
            assertEquals("member", login.role());
            assertTrue(client.isLoggedIn());
            // 无需再传 token——自动注入（设计确认 #4）
            EnterpriseApiClient.ChatResult result = client.chat(null, "你好");
            assertEquals("服务端回复", result.text());
            assertEquals("s-1", result.sessionId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 会话列表重命名删除消息全链路往返() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", exchange ->
                respond(exchange, "{\"token\":\"t\",\"role\":\"member\",\"username\":\"u\"}"));
        server.createContext("/api/sessions", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (method.equals("GET") && path.equals("/api/sessions")) {
                respond(exchange, "[{\"id\":\"s-1\",\"name\":\"会话A\","
                        + "\"createdAt\":\"2026-08-29T10:00:00Z\"}]");
            } else if (method.equals("PATCH") && path.equals("/api/sessions/s-1/rename")) {
                respond(exchange, "{\"id\":\"s-1\",\"name\":\"新名字\","
                        + "\"createdAt\":\"2026-08-29T10:00:00Z\"}");
            } else if (method.equals("DELETE") && path.equals("/api/sessions/s-1")) {
                respond(exchange, "");
            } else if (method.equals("GET") && path.equals("/api/sessions/s-1/messages")) {
                respond(exchange, "[{\"role\":\"user\",\"content\":\"问题\","
                        + "\"createdAt\":\"2026-08-29T10:00:00Z\"}]");
            } else {
                byte[] error = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, error.length);
                exchange.getResponseBody().write(error);
                exchange.close();
            }
        });
        server.start();
        try {
            EnterpriseApiClient client = new EnterpriseApiClient(
                    "http://localhost:" + server.getAddress().getPort());
            client.login("user", "pw");

            assertEquals(1, client.listSessions().size());
            assertEquals("会话A", client.listSessions().get(0).name());
            assertEquals("新名字", client.renameSession("s-1", "新名字").name());
            client.deleteSession("s-1"); // 不抛即成功
            assertEquals("问题", client.sessionMessages("s-1").get(0).content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 登录失败抛异常且不带token() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", exchange -> {
            byte[] error = "{\"message\":\"bad\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
        });
        server.start();
        try {
            EnterpriseApiClient client = new EnterpriseApiClient(
                    "http://localhost:" + server.getAddress().getPort());
            assertThrows(IllegalStateException.class, () -> client.login("user", "wrong"));
            assertTrue(!client.isLoggedIn());
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
