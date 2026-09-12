package com.omniforge.gateway.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.gateway.ImInboundMessage;
import com.omniforge.gateway.ImReplySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 钉钉回复发送器：对消息的 sessionWebhook 发 HTTP POST（带 timestamp+sign 加签），
 * 纯 JDK HttpClient 实现（零 SDK）。
 */
public class DingTalkReplySender implements ImReplySender {

    private static final Logger log = LoggerFactory.getLogger(DingTalkReplySender.class);
    private static final int MAX_REPLY_CHARS = 1800; // 钉钉文本消息长度上限附近，截断保护

    private final DingTalkProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DingTalkReplySender(DingTalkProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper());
    }

    DingTalkReplySender(DingTalkProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String platform() {
        return DingTalkAdapter.PLATFORM;
    }

    @Override
    public void send(ImInboundMessage original, String replyText) throws Exception {
        String sessionWebhook = contextValue(original, "sessionWebhook");
        if (sessionWebhook == null || sessionWebhook.isBlank()) {
            throw new IllegalStateException("消息缺少 sessionWebhook，无法回复");
        }
        String content = replyText.length() > MAX_REPLY_CHARS
                ? replyText.substring(0, MAX_REPLY_CHARS) : replyText;
        long timestamp = System.currentTimeMillis();
        String signedUrl = sessionWebhook
                + (sessionWebhook.contains("?") ? "&" : "?")
                + "timestamp=" + timestamp
                + "&sign=" + URLEncoder.encode(sign(timestamp), StandardCharsets.UTF_8);
        String body = objectMapper.writeValueAsString(
                Map.of("msgtype", "text", "text", Map.of("content", content)));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(signedUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("钉钉回复失败：HTTP " + response.statusCode());
        }
        log.debug("钉钉回复已发送：messageId={}", original.messageId());
    }

    private String sign(long timestamp) {
        return properties.getSecret() == null ? "" : DingTalkSigner.sign(timestamp, properties.getSecret());
    }

    private static String contextValue(ImInboundMessage message, String key) {
        Map<String, Object> context = message.platformContext();
        if (context == null || context.get(key) == null) {
            return null;
        }
        return context.get(key).toString();
    }
}
