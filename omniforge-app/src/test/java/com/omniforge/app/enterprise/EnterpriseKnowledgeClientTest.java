package com.omniforge.app.enterprise;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 企业知识库客户端测试：清单/分类/检索/删除解析。 */
class EnterpriseKnowledgeClientTest {

    @Test
    void 知识库清单检索与删除() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", exchange ->
                respond(exchange, "{\"token\":\"t\",\"role\":\"member\",\"username\":\"u\"}"));
        server.createContext("/api/knowledge/documents", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, "[{\"fileName\":\"a.txt\",\"category\":\"产品手册\","
                        + "\"chunks\":2,\"chars\":100}]");
            } else {
                error(exchange, 400, "未知方法");
            }
        });
        server.createContext("/api/knowledge/categories", exchange ->
                respond(exchange, "[\"产品手册\"]"));
        server.createContext("/api/knowledge/search", exchange ->
                respond(exchange, "[{\"fileName\":\"a.txt\",\"chunkIndex\":0,"
                        + "\"content\":\"内容片段\",\"score\":0.9}]"));
        server.createContext("/api/knowledge/documents/a.txt", exchange ->
                respond(exchange, ""));
        server.start();
        try {
            EnterpriseApiClient client = new EnterpriseApiClient(
                    "http://localhost:" + server.getAddress().getPort());
            client.login("u", "pw");

            var docs = client.listKbDocuments();
            assertEquals(1, docs.size());
            assertEquals("a.txt", docs.get(0).fileName());
            assertEquals("产品手册", docs.get(0).category());
            assertEquals(1, client.listKbCategories().size());
            assertEquals("内容片段", client.kbSearch("q", 5, null).get(0).content());
            client.kbDeleteDocument("a.txt"); // 不抛即成功
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

    private static void error(com.sun.net.httpserver.HttpExchange exchange, int status, String message)
            throws java.io.IOException {
        byte[] bytes = ("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
