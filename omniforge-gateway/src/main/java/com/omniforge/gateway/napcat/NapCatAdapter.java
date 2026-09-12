package com.omniforge.gateway.napcat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.gateway.IMessageAdapter;
import com.omniforge.gateway.ImInboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * NapCat（QQ · OneBot 11）适配器：HTTP POST 事件上报解析（技术预览）。
 *
 * <p>载荷为 OneBot 11 message 事件：post_type=message，message_type=group|private，
 * message 字段支持 array（分段，含 type=text/at/…）或 string（CQ 码文本）两种上报格式。
 * 文本提取仅取 type="text" 片段（补充建议 #4）；群聊 @ 检测：at 段 qq == self_id
 * （array 格式）或 raw_message 含 [CQ:at,qq=selfId|all]（string 格式）。
 * 非 message 事件（心跳/元事件）返回 null，由接收器忽略。</p>
 *
 * <p>鉴权：Authorization: Bearer &lt;token&gt; 常量时间比对（未配置 token 时校验关闭，
 * 但接收器会拒绝启动——双层防护）。</p>
 */
public class NapCatAdapter implements IMessageAdapter {

    public static final String PLATFORM = "qq";

    private static final Logger log = LoggerFactory.getLogger(NapCatAdapter.class);

    private final NapCatProperties properties;
    private final ObjectMapper objectMapper;

    public NapCatAdapter(NapCatProperties properties) {
        this(properties, new ObjectMapper());
    }

    NapCatAdapter(NapCatProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public String displayName() {
        return "QQ（NapCat）";
    }

    @Override
    public ImInboundMessage parse(Map<String, String> headers, String body) {
        // 1. Bearer token 常量时间比对（配置了 token 时强制）
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            String authorization = headers.getOrDefault("Authorization", "");
            String expected = "Bearer " + properties.getToken();
            if (!constantTimeEquals(authorization, expected)) {
                throw new IllegalArgumentException("NapCat 上报 token 校验失败");
            }
        }
        // 2. 载荷解析
        try {
            JsonNode root = objectMapper.readTree(body);
            String postType = root.path("post_type").asText("");
            if (!"message".equals(postType)) {
                return null; // 心跳/元事件：无需处理
            }
            String messageId = textValue(root, "message_id");
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("NapCat 载荷缺少 message_id");
            }
            String selfId = textValue(root, "self_id");
            String messageType = root.path("message_type").asText("");
            String userId = textValue(root, "user_id");
            String groupId = textValue(root, "group_id");

            String text = extractText(root);
            boolean atMention = detectAtMention(root, selfId);

            Map<String, Object> context = new HashMap<>();
            context.put("messageType", messageType);
            context.put("userId", userId);
            if ("group".equals(messageType)) {
                context.put("groupId", groupId);
            }
            return new ImInboundMessage(PLATFORM, messageId, userId, text, atMention, null, context);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("NapCat 载荷解析失败：" + e.getMessage(), e);
        }
    }

    /** 文本提取：array 格式仅取 type=text 片段；string 格式剔除 CQ 码保留纯文本 */
    static String extractText(JsonNode root) {
        JsonNode message = root.get("message");
        if (message == null || message.isNull()) {
            return "";
        }
        if (message.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode segment : message) {
                if ("text".equals(segment.path("type").asText(""))) {
                    sb.append(segment.path("data").path("text").asText(""));
                }
                // at/image 等非文本类型忽略（补充建议 #4）
            }
            return sb.toString().trim();
        }
        return message.asText("")
                .replaceAll("\\[CQ:[^\\]]+]", "")
                .trim();
    }

    /** @ 检测：array 格式查 at 段 qq == selfId；string 格式查 raw_message 的 CQ:at */
    static boolean detectAtMention(JsonNode root, String selfId) {
        JsonNode message = root.get("message");
        if (message != null && message.isArray()) {
            for (JsonNode segment : message) {
                if ("at".equals(segment.path("type").asText(""))
                        && selfId != null && selfId.equals(textValue(segment.path("data"), "qq"))) {
                    return true;
                }
            }
            return false;
        }
        String raw = root.path("raw_message").asText("");
        return raw.matches(".*\\[CQ:at,qq=" + java.util.regex.Pattern.quote(selfId == null ? "" : selfId) + "].*")
                || raw.contains("[CQ:at,qq=all]");
    }

    /** 数值型字段兼容：message_id/user_id 可能是大整数（负数群消息 ID），统一按文本处理 */
    private static String textValue(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (node.isNumber()) {
            return String.valueOf(node.longValue());
        }
        return node.asText("");
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
