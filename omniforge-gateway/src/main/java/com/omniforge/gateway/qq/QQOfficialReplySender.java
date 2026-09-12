package com.omniforge.gateway.qq;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QQ 官方机器人回复发送器：单聊 {@code POST /v2/users/{user_openid}/messages}、
 * 群聊 {@code POST /v2/groups/{group_openid}/messages}（纯 JDK HttpClient，零新依赖）。
 *
 * <p>被动回复携带 msg_id（来源事件 id）+ 递增 msg_seq（相同 msg_id 重复发送防重，
 * 官方规则：相同 msg_id+msg_seq 重复发送会失败）。超长文本截断并追加提示。
 * 响应按 err_code 判定成败（message 字段可能调整，不依赖）。</p>
 */
public class QQOfficialReplySender implements ImReplySender {

    private static final Logger log = LoggerFactory.getLogger(QQOfficialReplySender.class);
    private static final int MAX_REPLY_CHARS = 1800;

    private final QqSettingsStore settingsStore;
    private final QqAccessTokenProvider tokenProvider;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** messageId → 已用 msg_seq（被动回复序号递增） */
    private final ConcurrentHashMap<String, AtomicInteger> seqCounters = new ConcurrentHashMap<>();

    public QQOfficialReplySender(QqSettingsStore settingsStore, QqAccessTokenProvider tokenProvider) {
        this(settingsStore, tokenProvider, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper());
    }

    QQOfficialReplySender(QqSettingsStore settingsStore, QqAccessTokenProvider tokenProvider,
                          HttpClient httpClient, ObjectMapper objectMapper) {
        this.settingsStore = settingsStore;
        this.tokenProvider = tokenProvider;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String platform() {
        return QQOfficialAdapter.PLATFORM;
    }

    @Override
    public void send(ImInboundMessage original, String replyText) throws Exception {
        String kind = contextValue(original, "kind");
        String target = "group".equals(kind)
                ? contextValue(original, "groupOpenid")
                : contextValue(original, "userOpenid");
        if (target == null || target.isBlank()) {
            throw new IllegalStateException("QQ 官方消息缺少回发目标（groupOpenid/userOpenid）");
        }
        QqSettings settings = settingsStore.current().normalize();
        String token = tokenProvider.getToken();
        String content = replyText.length() > MAX_REPLY_CHARS
                ? replyText.substring(0, MAX_REPLY_CHARS) + "（消息过长已截断）"
                : replyText;

        int seq = seqCounters
                .computeIfAbsent(original.messageId(), key -> new AtomicInteger(0))
                .incrementAndGet();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("msg_type", 0);
        payload.put("msg_id", original.messageId());
        payload.put("msg_seq", seq);

        String path = "group".equals(kind)
                ? "/v2/groups/" + target + "/messages"
                : "/v2/users/" + target + "/messages";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(settings.apiBaseUrl() + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "QQBot " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode json = null;
        try {
            json = objectMapper.readTree(response.body());
        } catch (Exception ignored) {
            // 响应非 JSON 时按 HTTP 状态判定
        }
        boolean httpOk = response.statusCode() >= 200 && response.statusCode() < 300;
        long errCode = json == null ? -1 : json.path("err_code").asLong(httpOk ? 0 : -1);
        if (!httpOk || errCode != 0) {
            String message = json == null ? "HTTP " + response.statusCode()
                    : json.path("message").asText("HTTP " + response.statusCode());
            throw new IllegalStateException("QQ 官方回复失败：err_code=" + errCode + " " + message);
        }
        log.debug("QQ 官方回复已发送：messageId={}，msgSeq={}，长度={}", original.messageId(), seq, content.length());
    }

    private static String contextValue(ImInboundMessage message, String key) {
        Object value = message.platformContext() == null ? null : message.platformContext().get(key);
        return value == null ? null : value.toString();
    }
}
