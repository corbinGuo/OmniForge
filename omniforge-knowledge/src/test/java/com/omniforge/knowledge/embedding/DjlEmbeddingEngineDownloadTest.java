package com.omniforge.knowledge.embedding;

import com.omniforge.knowledge.KnowledgeProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DJL 引擎模型下载路径测试：本地 HttpServer 模拟 HuggingFace 镜像，
 * 验证 ONNX 权重取自仓库 onnx/ 子目录、分词器文件取自根目录。
 */
class DjlEmbeddingEngineDownloadTest {

    @TempDir
    Path tempDir;

    @Test
    void 从仓库路径下载权重与分词器文件() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        Map<String, String> files = Map.of(
                "/repo/resolve/main/onnx/model.onnx", "onnx-weights",
                "/repo/resolve/main/tokenizer.json", "{}",
                "/repo/resolve/main/tokenizer_config.json", "{}",
                "/repo/resolve/main/vocab.txt", "[UNK]",
                "/repo/resolve/main/special_tokens_map.json", "{}");
        for (Map.Entry<String, String> entry : files.entrySet()) {
            byte[] body = entry.getValue().getBytes(StandardCharsets.UTF_8);
            server.createContext(entry.getKey(), exchange -> {
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
        }
        server.start();
        try {
            KnowledgeProperties properties = new KnowledgeProperties();
            properties.setModelName("repo");
            properties.setModelDir(tempDir);
            properties.setHuggingFaceBaseUrl("http://localhost:" + server.getAddress().getPort() + "/");

            new DjlEmbeddingEngine(properties).downloadModel();

            assertTrue(Files.exists(tempDir.resolve("model.onnx")), "ONNX 权重应落盘为本地 model.onnx");
            assertEquals("onnx-weights", Files.readString(tempDir.resolve("model.onnx")));
            assertTrue(Files.exists(tempDir.resolve("tokenizer.json")));
            assertTrue(Files.exists(tempDir.resolve("vocab.txt")));
            assertTrue(Files.exists(tempDir.resolve("tokenizer_config.json")));
            assertTrue(Files.exists(tempDir.resolve("special_tokens_map.json")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 下载404时抛出明确错误() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> exchange.sendResponseHeaders(404, -1));
        server.start();
        try {
            KnowledgeProperties properties = new KnowledgeProperties();
            properties.setModelName("repo");
            properties.setModelDir(tempDir);
            properties.setHuggingFaceBaseUrl("http://localhost:" + server.getAddress().getPort() + "/");

            IOException error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                    () -> new DjlEmbeddingEngine(properties).downloadModel());
            assertTrue(error.getMessage().contains("404"), "应携带 HTTP 状态码：" + error.getMessage());
        } finally {
            server.stop(0);
        }
    }
}
