package com.omniforge.app.enterprise;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 企业版多模型协作客户端测试（方案 B，JDK HttpServer 桩服务）：模型清单 + 辩论响应解析。 */
class EnterpriseDebateClientTest {

    @Test
    void 模型清单与辩论响应解析() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", exchange ->
                respond(exchange, "{\"token\":\"t\",\"role\":\"member\",\"username\":\"u\"}"));
        server.createContext("/api/models", exchange ->
                respond(exchange, "[{\"alias\":\"m1\",\"providerName\":\"p1\",\"contextWindowTokens\":8192},"
                        + "{\"alias\":\"m2\",\"providerName\":\"p2\",\"contextWindowTokens\":null}]"));
        server.createContext("/api/agent/debate", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.contains("\"mode\":\"debate\"") || !body.contains("\"aliases\"")) {
                error(exchange, 400, "参数缺失");
                return;
            }
            respond(exchange, "{\"text\":\"辩论结束\\n◆ m1：观点一\\n◆ m2：观点二\",\"mode\":\"debate\","
                    + "\"aliases\":[\"m1\",\"m2\"],\"judgeAlias\":\"m1\","
                    + "\"judgeVerdicts\":[{\"round\":1,\"text\":\"旗鼓相当\"}],"
                    + "\"finalRound\":{\"m1\":\"观点一\",\"m2\":\"观点二\"},"
                    + "\"winnerAlias\":\"m1\",\"stopReason\":\"JUDGE_WINNER\",\"maxRounds\":3}");
        });
        server.start();
        try {
            EnterpriseApiClient client = new EnterpriseApiClient(
                    "http://localhost:" + server.getAddress().getPort());
            client.login("user", "pw");

            var models = client.listModels();
            assertEquals(2, models.size());
            assertEquals("m1", models.get(0).alias());
            assertEquals("p1", models.get(0).providerName());
            assertEquals(8192, models.get(0).contextWindowTokens());

            var outcome = client.runDebate(new EnterpriseApiClient.DebateRun(
                    "主题", java.util.List.of("m1", "m2"), "m1", "debate", 3, null));
            assertTrue(outcome.text().contains("辩论结束"));
            assertEquals("JUDGE_WINNER", outcome.stopReason());
            assertEquals("m1", outcome.winnerAlias());
            assertEquals(1, outcome.judgeVerdicts().size());
            assertEquals("观点一", outcome.finalRound().get("m1"));
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
