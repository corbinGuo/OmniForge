package com.omniforge.knowledge.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 远程 Embedding 引擎（决议 #8：支持配置 Ollama 等远程 Embedding 服务，绕过本地模型下载）。
 * 协议：POST {baseUrl}/api/embed {"model": "...", "input": "..."} → {"embeddings": [[...]]}。
 */
public class RemoteEmbeddingEngine implements EmbeddingEngine {

    private static final Logger log = LoggerFactory.getLogger(RemoteEmbeddingEngine.class);

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile int dimensions = 0;

    public RemoteEmbeddingEngine(String baseUrl, String model) {
        this(baseUrl, model, HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper());
    }

    RemoteEmbeddingEngine(String baseUrl, String model, HttpClient httpClient, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model == null || model.isBlank() ? "nomic-embed-text" : model;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public float[] embed(String text) {
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("model", model, "input", text));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embed"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("远程 Embedding 服务返回 HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode first = root.path("embeddings").path(0);
            if (!first.isArray()) {
                throw new IllegalStateException("远程 Embedding 响应缺少 embeddings");
            }
            List<Float> floats = new ArrayList<>();
            first.forEach(node -> floats.add((float) node.asDouble()));
            float[] vector = new float[floats.size()];
            for (int i = 0; i < floats.size(); i++) {
                vector[i] = floats.get(i);
            }
            dimensions = vector.length;
            return vector;
        } catch (Exception e) {
            throw new IllegalStateException("远程 Embedding 调用失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        return true; // 配置即视为可用（调用失败由 embed 抛错，共识检测器会降级跳过）
    }

    @Override
    public String description() {
        return "远程 Embedding（" + baseUrl + " · " + model + "）";
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    /** 无资源需释放 */
    public void close() {
        log.debug("远程 Embedding 引擎关闭（无资源）");
    }
}
