package com.omniforge.gateway.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QQ 官方 access_token 管理（client_credentials）：
 * {@code POST {base}/app/getAppAccessToken}，内存缓存 + 到期前 60 秒自动刷新
 * （官方规则：有效期内重复获取返回相同值，到期前 60 秒内获取返回新 token）。
 *
 * <p>缓存键含 AppID/AppSecret：配置热切换后旧 token 自动失效。</p>
 */
public class QqAccessTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(QqAccessTokenProvider.class);
    private static final long REFRESH_AHEAD_MS = 60_000; // 官方建议到期前 60 秒刷新

    private final QqSettingsStore settingsStore;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile CachedToken cached;

    public QqAccessTokenProvider(QqSettingsStore settingsStore) {
        this(settingsStore, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper());
    }

    QqAccessTokenProvider(QqSettingsStore settingsStore, HttpClient httpClient, ObjectMapper objectMapper) {
        this.settingsStore = settingsStore;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取有效 access_token（必要时刷新）。线程安全（synchronized 串行化刷新）。
     *
     * @throws IllegalStateException 未配置凭证时抛出
     * @throws IOException           网络/接口失败（含官方错误码信息）
     */
    public synchronized String getToken() throws IOException {
        QqSettings settings = settingsStore.current().normalize();
        if (!settings.hasCredentials()) {
            throw new IllegalStateException("QQ 机器人未配置 AppID/AppSecret（配置中心 → QQ 机器人）");
        }
        long now = System.currentTimeMillis();
        CachedToken current = cached;
        if (current != null && current.appId.equals(settings.appId())
                && current.appSecret.equals(settings.appSecret())
                && now < current.expiresAtEpochMs) {
            return current.token;
        }
        return refresh(settings);
    }

    /** 强制刷新（配置变更/手动触发） */
    public synchronized String refresh(QqSettings settings) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appId", settings.appId());
        body.put("clientSecret", settings.appSecret());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(settings.apiBaseUrl() + "/app/getAppAccessToken"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("access_token 获取被中断", e);
        }
        JsonNode json;
        try {
            json = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new IOException("access_token 响应解析失败（HTTP " + response.statusCode() + "）: "
                    + response.body(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || json.path("access_token").asText("").isBlank()) {
            String errCode = json.path("err_code").asText(json.path("code").asText(""));
            String message = json.path("message").asText(json.path("error").asText("HTTP " + response.statusCode()));
            throw new IOException("access_token 获取失败：" + errCode + " " + message);
        }
        String token = json.path("access_token").asText();
        long expiresInSeconds = json.path("expires_in").asLong(7200);
        long expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000) - REFRESH_AHEAD_MS;
        cached = new CachedToken(token, expiresAt, settings.appId(), settings.appSecret());
        log.info("QQ access_token 已刷新（有效期 {} 秒）", expiresInSeconds);
        return token;
    }

    private record CachedToken(String token, long expiresAtEpochMs, String appId, String appSecret) {
    }
}
