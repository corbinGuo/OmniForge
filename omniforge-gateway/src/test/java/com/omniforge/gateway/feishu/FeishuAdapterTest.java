package com.omniforge.gateway.feishu;

import com.omniforge.gateway.ImInboundMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuAdapterTest {

    private static final String EVENT = """
            {"type":"event_callback",
             "event":{"sender":{"sender_id":{"open_id":"ou_user_1"}},
                      "message":{"message_id":"om_msg_1","chat_id":"oc_chat_1",
                                 "content":"{\\"text\\":\\"你好飞书\\"}"}}}
            """;

    @Test
    void 解析事件回调() {
        FeishuAdapter adapter = new FeishuAdapter(new FeishuProperties());
        ImInboundMessage message = adapter.parse(Map.of(), EVENT);

        assertEquals("om_msg_1", message.messageId());
        assertEquals("ou_user_1", message.from());
        assertEquals("你好飞书", message.text());
        assertEquals("oc_chat_1", message.platformContext().get("chatId"));
    }

    @Test
    void 签名校验通过与拒绝() {
        FeishuProperties properties = new FeishuProperties();
        properties.setEncryptKey("encrypt-key");
        FeishuAdapter adapter = new FeishuAdapter(properties);

        String timestamp = "1720000000";
        String nonce = "n1";
        String signature = sha256(timestamp + nonce + "encrypt-key" + EVENT);
        adapter.parse(Map.of("X-Lark-Signature", signature, "timestamp", timestamp, "nonce", nonce), EVENT);

        assertThrows(IllegalArgumentException.class, () -> adapter.parse(
                Map.of("X-Lark-Signature", signature + "x", "timestamp", timestamp, "nonce", nonce),
                EVENT));
    }

    @Test
    void 非事件类型被拒绝() {
        FeishuAdapter adapter = new FeishuAdapter(new FeishuProperties());
        assertThrows(IllegalArgumentException.class, () ->
                adapter.parse(Map.of(), "{\"type\":\"url_verification\",\"challenge\":\"c\"}"));
    }

    @Test
    void 提取content文本() {
        assertEquals("内容", FeishuAdapter.extractText("{\"text\":\"内容\"}"));
        assertEquals("raw", FeishuAdapter.extractText("raw"));
        assertEquals("", FeishuAdapter.extractText(""));
    }

    @Test
    void 卡片JSON转义() {
        String card = FeishuReplySender.buildCardJson("带\"引号\"内容");
        assertTrue(card.contains("\\\"引号\\\""));
        assertTrue(card.contains("wide_screen_mode"));
    }

    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
