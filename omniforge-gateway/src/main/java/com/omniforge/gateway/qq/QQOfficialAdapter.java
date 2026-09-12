package com.omniforge.gateway.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.gateway.IMessageAdapter;
import com.omniforge.gateway.ImInboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * QQ 官方机器人适配器（官方 API v2，platform=qqofficial）：解析网关事件 payload
 * {@code {id, op, d, s, t}} 中的消息事件（WebSocket 与 Webhook 同构，二者共用本适配器）。
 *
 * <p>处理事件（intent 1<<25）：{@code C2C_MESSAGE_CREATE}（单聊）与
 * {@code GROUP_AT_MESSAGE_CREATE}（群@，content 已由平台去除@前缀，atMention 恒 true）。
 * 仅取 message_type=0（纯文本）与 103（引用消息）的 content；3（结构化卡片）等跳过。
 * 非 opcode 0 / 非消息事件返回 null，由接收器忽略。</p>
 *
 * <p>签名校验位于接收器层（Webhook Ed25519 / WebSocket 握手鉴权），适配器保持纯解析。</p>
 */
public class QQOfficialAdapter implements IMessageAdapter {

    public static final String PLATFORM = "qqofficial";

    private static final Logger log = LoggerFactory.getLogger(QQOfficialAdapter.class);

    private final ObjectMapper objectMapper;

    public QQOfficialAdapter() {
        this(new ObjectMapper());
    }

    QQOfficialAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public String displayName() {
        return "QQ 官方机器人";
    }

    @Override
    public ImInboundMessage parse(Map<String, String> headers, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            int op = root.path("op").asInt(-1);
            if (op != 0) {
                return null; // 心跳/鉴权/验证等非事件载荷
            }
            String eventType = root.path("t").asText("");
            boolean c2c = "C2C_MESSAGE_CREATE".equals(eventType);
            boolean groupAt = "GROUP_AT_MESSAGE_CREATE".equals(eventType);
            if (!c2c && !groupAt) {
                return null; // READY/RESUMED/好友事件等：无需处理
            }
            JsonNode d = root.path("d");
            int messageType = d.path("message_type").asInt(0);
            if (messageType != 0 && messageType != 103) {
                log.debug("QQ 消息跳过（message_type={}，仅处理文本/引用消息）", messageType);
                return null;
            }
            String messageId = d.path("id").asText("");
            if (messageId.isBlank()) {
                throw new IllegalArgumentException("QQ 消息事件缺少 id");
            }
            String text = d.path("content").asText("").trim();
            if (text.isEmpty()) {
                return null; // 纯附件/空文本消息：无可处理内容
            }
            String userOpenid = d.path("author").path("user_openid").asText("");
            String memberOpenid = d.path("author").path("member_openid").asText("");
            Map<String, Object> context = new HashMap<>();
            String from;
            if (c2c) {
                context.put("kind", "c2c");
                context.put("userOpenid", userOpenid);
                from = userOpenid;
            } else {
                context.put("kind", "group");
                context.put("groupOpenid", d.path("group_openid").asText(""));
                context.put("memberOpenid", memberOpenid);
                from = memberOpenid;
            }
            context.put("msgId", messageId);
            return new ImInboundMessage(PLATFORM, messageId, from, text, groupAt, null, context);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("QQ 官方载荷解析失败：" + e.getMessage(), e);
        }
    }
}
