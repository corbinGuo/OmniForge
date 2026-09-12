package com.omniforge.app.headless;

import com.omniforge.core.gateway.GatewayUsage;
import com.omniforge.core.gateway.ModelGateway;
import com.omniforge.core.gateway.ModelInfo;
import com.omniforge.core.gateway.TokenUsage;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeadlessHttpServerTest {

    @Test
    void 健康检查JSON格式() {
        String json = HeadlessHttpServer.healthJson("UP", 3, "UP");
        assertTrue(json.contains("\"status\":\"UP\""));
        assertTrue(json.contains("\"models\":3"));
        assertTrue(json.contains("\"db\":\"UP\""));
        assertTrue(json.contains("\"timestamp\":\""));
    }

    @Test
    void 指标Prometheus文本格式() {
        String metrics = HeadlessHttpServer.metricsText(2, 42, 0.123);
        assertTrue(metrics.contains("omniforge_models_available 2"));
        assertTrue(metrics.contains("omniforge_total_calls 42"));
        assertTrue(metrics.contains("omniforge_total_cost_usd 0.123"));
        assertTrue(metrics.contains("jvm_memory_used_bytes"));
    }

    @Test
    void 端到端HTTP冒烟() throws Exception {
        ModelGateway modelGateway = mock(ModelGateway.class);
        when(modelGateway.availableModels()).thenReturn(List.of());
        when(modelGateway.usage()).thenReturn(new GatewayUsage(Map.of(), 0, 0, 0, 0.0));
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));

        HeadlessProperties properties = new HeadlessProperties();
        properties.setPort(0);
        HeadlessHttpServer server = new HeadlessHttpServer(properties, modelGateway, dataSource);
        server.start();
        try {
            var field = HeadlessHttpServer.class.getDeclaredField("server");
            field.setAccessible(true);
            com.sun.net.httpserver.HttpServer httpServer =
                    (com.sun.net.httpserver.HttpServer) field.get(server);
            int port = httpServer.getAddress().getPort();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"DEGRADED\""));

            HttpResponse<String> metrics = client.send(
                    HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("omniforge_models_available 0"));
        } finally {
            server.stop();
        }
    }
}
