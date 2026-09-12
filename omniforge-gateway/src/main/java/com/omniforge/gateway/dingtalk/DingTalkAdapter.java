package com.omniforge.gateway.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.gateway.IMessageAdapter;
import com.omniforge.gateway.ImInboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 钉钉适配器（纯 HTTP + Mac 签名，零 SDK，Step 1 预检决策）。
 *
 * <p>Outgoing Webhook 回调：钉钉 POST JSON 载荷（msgId/senderId/senderNick/
 * text.content/sessionWebhook…），请求头带 timestamp 与 sign（HmacSHA256）。
 * 配置 secret 后签名校验生效；sessionWebhook 存在视为被 @（单聊/群 @ 才有会话回发地址）。</p>
 */
public class DingTalkAdapter implements IMessageAdapter {

    public static final String PLATFORM = "dingtalk";

    private static final Logger log = LoggerFactory.getLogger(DingTalkAdapter.class);

    private final DingTalkProperties properties;
    private final ObjectMapper objectMapper;

    public DingTalkAdapter(DingTalkProperties properties) {
        this(properties, new ObjectMapper());
    }

    DingTalkAdapter(DingTalkProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public String displayName() {
        return "钉钉";
    }

    @Override
    public ImInboundMessage parse(Map<String, String> headers, String body) {
        // 1. 签名校验（配置了 secret 时强制）
        if (properties.getSecret() != null && !properties.getSecret().isBlank()) {
            try {
                long timestamp = Long.parseLong(headers.getOrDefault("timestamp", "0"));
                String sign = headers.get("sign");
                if (!DingTalkSigner.verify(timestamp, sign, properties.getSecret())) {
                    throw new IllegalArgumentException("钉钉回调签名校验失败");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("钉钉回调缺少合法 timestamp 头", e);
            }
        }
        // 2. 载荷解析
        try {
            JsonNode root = objectMapper.readTree(body);
            String msgId = root.path("msgId").asText("");
            if (msgId.isBlank()) {
                throw new IllegalArgumentException("钉钉载荷缺少 msgId");
            }
            String text = root.path("text").path("content").asText("");
            String sender = root.path("senderId").asText(root.path("senderNick").asText(""));
            boolean atMention = !root.path("sessionWebhook").asText("").isBlank();
            Map<String, Object> context = new HashMap<>();
            String sessionWebhook = root.path("sessionWebhook").asText("");
            if (!sessionWebhook.isBlank()) {
                context.put("sessionWebhook", sessionWebhook);
            }
            context.put("conversationId", root.path("conversationId").asText(""));
            return new ImInboundMessage(PLATFORM, msgId, sender, text, atMention, null, context);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("钉钉载荷解析失败：" + e.getMessage(), e);
        }
    }
}
