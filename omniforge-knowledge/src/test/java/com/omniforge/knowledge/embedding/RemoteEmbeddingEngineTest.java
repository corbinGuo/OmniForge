package com.omniforge.knowledge.embedding;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 远程 Embedding 引擎测试（JDK HttpServer 桩 Ollama 服务）。 */
class RemoteEmbeddingEngineTest {

    @Test
    void 调用远程服务返回向量() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/embed", exchange -> {
            String body = "{\"embeddings\":[[0.1,0.2,0.3,0.4]]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            RemoteEmbeddingEngine engine = new RemoteEmbeddingEngine(
                    "http://localhost:" + server.getAddress().getPort(), "nomic-embed-text");
            assertTrue(engine.isAvailable());
            float[] vector = engine.embed("你好");
            assertEquals(4, vector.length);
            assertEquals(0.1f, vector[0], 0.001f);
            assertEquals(4, engine.dimensions());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 服务错误抛异常() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/embed", exchange -> {
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            RemoteEmbeddingEngine engine = new RemoteEmbeddingEngine(
                    "http://localhost:" + server.getAddress().getPort(), "m");
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> engine.embed("x"));
        } finally {
            server.stop(0);
        }
    }
}
