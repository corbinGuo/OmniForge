package com.omniforge.gateway.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.gateway.IMessageAdapter;
import com.omniforge.gateway.ImInboundMessage;

import java.util.HashMap;
import java.util.Map;

/**
 * 飞书适配器（oapi-sdk 2.4.14）：解析事件回调（event_callback）为统一消息，
 * X-Lark-Signature 签名校验（未加密场景）。
 * 回复经 {@link FeishuReplySender} 以交互卡片发送（platformContext 携带 chat_id）。</p>
 */
public class FeishuAdapter implements IMessageAdapter {

    public static final String PLATFORM = "feishu";

    private final FeishuProperties properties;
    private final ObjectMapper objectMapper;

    public FeishuAdapter(FeishuProperties properties) {
        this(properties, new ObjectMapper());
    }

    FeishuAdapter(FeishuProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public String displayName() {
        return "飞书";
    }

    @Override
    public ImInboundMessage parse(Map<String, String> headers, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String type = root.path("type").asText("");
            if (!"event_callback".equals(type)) {
                throw new IllegalArgumentException("非事件回调类型：" + type);
            }
            // 签名校验（encryptKey 配置时）
            if (properties.getEncryptKey() != null && !properties.getEncryptKey().isBlank()) {
                String signature = headers.getOrDefault("X-Lark-Signature", "");
                if (!FeishuSigner.verify(headers.getOrDefault("timestamp", ""),
                        headers.getOrDefault("nonce", ""),
                        properties.getEncryptKey(), body, signature)) {
                    throw new IllegalArgumentException("飞书回调签名校验失败");
                }
            }
            JsonNode event = root.path("event");
            JsonNode message = event.path("message");
            String messageId = message.path("message_id").asText("");
            if (messageId.isBlank()) {
                throw new IllegalArgumentException("飞书载荷缺少 message_id");
            }
            String chatId = message.path("chat_id").asText("");
            String sender = event.path("sender").path("sender_id").path("open_id").asText("");
            String contentJson = message.path("content").asText("");
            String text = extractText(contentJson);
            Map<String, Object> context = new HashMap<>();
            context.put("chatId", chatId);
            return new ImInboundMessage(PLATFORM, messageId, sender, text, false, null, context);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("飞书载荷解析失败：" + e.getMessage(), e);
        }
    }

    /** 飞书消息 content 是 JSON 字符串（{"text":"..."}），提取文本 */
    static String extractText(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        try {
            JsonNode content = new ObjectMapper().readTree(contentJson);
            return content.path("text").asText("");
        } catch (Exception e) {
            return contentJson; // 非 JSON 内容原样返回
        }
    }
}
