package com.omniforge.gateway.napcat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.gateway.ImInboundMessage;
import com.omniforge.gateway.ImReplySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NapCat 回复发送器：OneBot 11 HTTP API {@code /send_msg}（Bearer token），
 * 纯 JDK HttpClient（零新依赖）。群聊带 group_id、私聊带 user_id，
 * 超长文本截断并追加提示（补充建议 #3）。
 */
public class NapCatReplySender implements ImReplySender {

    private static final Logger log = LoggerFactory.getLogger(NapCatReplySender.class);
    private static final int MAX_REPLY_CHARS = 1800; // QQ 消息长度上限附近，截断保护

    private final NapCatProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public NapCatReplySender(NapCatProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper());
    }

    NapCatReplySender(NapCatProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String platform() {
        return NapCatAdapter.PLATFORM;
    }

    @Override
    public void send(ImInboundMessage original, String replyText) throws Exception {
        String messageType = contextValue(original, "messageType");
        String groupId = contextValue(original, "groupId");
        String userId = contextValue(original, "userId");
        Map<String, Object> payload = new LinkedHashMap<>();
        if ("group".equals(messageType) && groupId != null) {
            payload.put("message_type", "group");
            payload.put("group_id", Long.parseLong(groupId));
        } else if (userId != null) {
            payload.put("message_type", "private");
            payload.put("user_id", Long.parseLong(userId));
        } else {
            throw new IllegalStateException("NapCat 消息缺少回发目标（groupId/userId）");
        }
        String content = replyText.length() > MAX_REPLY_CHARS
                ? replyText.substring(0, MAX_REPLY_CHARS) + "（消息过长已截断）"
                : replyText;
        payload.put("message", java.util.List.of(Map.of(
                "type", "text",
                "data", Map.of("text", content))));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://" + properties.getNapcatHost() + ":" + properties.getNapcatPort()
                        + "/send_msg"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.getToken());
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("NapCat 回复失败：HTTP " + response.statusCode());
        }
        // OneBot 11 响应：{"status":"ok","retcode":0,...}；retcode 非 0 视为失败
        try {
            int retcode = objectMapper.readTree(response.body()).path("retcode").asInt(0);
            if (retcode != 0) {
                throw new IllegalStateException("NapCat 回复失败：retcode=" + retcode);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.debug("NapCat 回复响应解析跳过：{}", e.getMessage());
        }
        log.debug("NapCat 回复已发送：messageId={}，长度={}", original.messageId(), content.length());
    }

    private static String contextValue(ImInboundMessage message, String key) {
        Object value = message.platformContext() == null ? null : message.platformContext().get(key);
        return value == null ? null : value.toString();
    }
}
