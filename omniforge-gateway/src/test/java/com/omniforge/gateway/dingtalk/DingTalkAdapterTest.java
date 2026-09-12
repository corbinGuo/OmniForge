package com.omniforge.gateway.dingtalk;

import com.omniforge.gateway.ImInboundMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkAdapterTest {

    private static final String PAYLOAD = """
            {"msgId":"msg-001","senderId":"user-1","senderNick":"张三",
             "conversationId":"cid-1","sessionWebhook":"https://oapi.dingtalk.com/robot/send?access_token=xxx",
             "text":{"content":"@机器人 你好"}}
            """;

    @Test
    void 解析有效载荷() {
        DingTalkProperties properties = new DingTalkProperties();
        DingTalkAdapter adapter = new DingTalkAdapter(properties);

        ImInboundMessage message = adapter.parse(Map.of(), PAYLOAD);

        assertEquals("msg-001", message.messageId());
        assertEquals("user-1", message.from());
        assertTrue(message.text().contains("你好"));
        assertTrue(message.atMention(), "含 sessionWebhook 视为被 @");
        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=xxx",
                message.platformContext().get("sessionWebhook"));
    }

    @Test
    void 配置密钥后签名校验失败被拒绝() {
        DingTalkProperties properties = new DingTalkProperties();
        properties.setSecret("SECsecret");
        DingTalkAdapter adapter = new DingTalkAdapter(properties);

        long timestamp = 1720000000000L;
        String sign = DingTalkSigner.sign(timestamp, "SECsecret");
        // 正确签名 → 通过
        adapter.parse(Map.of("timestamp", String.valueOf(timestamp), "sign", sign), PAYLOAD);
        // 篡改签名 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> adapter.parse(
                Map.of("timestamp", String.valueOf(timestamp), "sign", sign + "x"), PAYLOAD));
        // 缺失时间戳 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> adapter.parse(
                Map.of("sign", sign), PAYLOAD));
    }

    @Test
    void 缺少msgId被拒绝() {
        DingTalkAdapter adapter = new DingTalkAdapter(new DingTalkProperties());
        assertThrows(IllegalArgumentException.class, () ->
                adapter.parse(Map.of(), "{\"text\":{\"content\":\"hi\"}}"));
    }

    @Test
    void 无会话回发地址时atMention为false() {
        DingTalkAdapter adapter = new DingTalkAdapter(new DingTalkProperties());
        ImInboundMessage message = adapter.parse(Map.of(),
                "{\"msgId\":\"m2\",\"senderNick\":\"李四\",\"text\":{\"content\":\"你好\"}}");
        assertFalse(message.atMention());
        assertTrue(message.platformContext() == null
                || !message.platformContext().containsKey("sessionWebhook"));
    }
}
