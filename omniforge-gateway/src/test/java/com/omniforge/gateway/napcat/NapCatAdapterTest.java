package com.omniforge.gateway.napcat;

import com.omniforge.gateway.ImInboundMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NapCat 适配器解析测试：OneBot 11 message 事件（array/string 两种上报格式）。 */
class NapCatAdapterTest {

    private static final String GROUP_EVENT = """
            {"time":1725123456,"self_id":"10001","post_type":"message","message_type":"group",
             "message_id":-2147483647,"group_id":"123456","user_id":"987654321",
             "message":[{"type":"at","data":{"qq":"10001"}},
                        {"type":"text","data":{"text":"你好"}},
                        {"type":"image","data":{"url":"http://x/y.png"}}],
             "raw_message":"[CQ:at,qq=10001] 你好","sender":{"user_id":"987654321"}}
            """;

    private static final String PRIVATE_EVENT = """
            {"time":1725123456,"self_id":"10001","post_type":"message","message_type":"private",
             "message_id":42,"user_id":"555",
             "message":"[CQ:face,id=1] 帮我写一段代码","raw_message":"[CQ:face,id=1] 帮我写一段代码"}
            """;

    private static final String HEARTBEAT = """
            {"time":1725123456,"self_id":"10001","post_type":"meta_event","meta_event_type":"heartbeat"}
            """;

    @Test
    void 群聊事件解析at检测与纯文本提取() {
        NapCatAdapter adapter = new NapCatAdapter(new NapCatProperties());
        ImInboundMessage message = adapter.parse(Map.of(), GROUP_EVENT);

        assertEquals("qq", message.platform());
        assertEquals("-2147483647", message.messageId());
        assertEquals("987654321", message.from());
        assertEquals("你好", message.text(), "仅提取 type=text 片段，忽略 at/image（补充建议 #4）");
        assertTrue(message.atMention(), "at 段 qq==self_id 视为被 @");
        assertEquals("group", message.platformContext().get("messageType"));
        assertEquals("123456", message.platformContext().get("groupId"));
    }

    @Test
    void 私聊string格式剔除CQ码() {
        NapCatAdapter adapter = new NapCatAdapter(new NapCatProperties());
        ImInboundMessage message = adapter.parse(Map.of(), PRIVATE_EVENT);

        assertEquals("帮我写一段代码", message.text());
        assertFalse(message.atMention());
        assertEquals("private", message.platformContext().get("messageType"));
        assertNull(message.platformContext().get("groupId"));
    }

    @Test
    void 心跳元事件返回null() {
        NapCatAdapter adapter = new NapCatAdapter(new NapCatProperties());
        assertNull(adapter.parse(Map.of(), HEARTBEAT));
    }

    @Test
    void 配置token后校验失败被拒绝() {
        NapCatProperties properties = new NapCatProperties();
        properties.setToken("SECRET");
        NapCatAdapter adapter = new NapCatAdapter(properties);

        // 正确 token → 通过
        adapter.parse(Map.of("Authorization", "Bearer SECRET"), GROUP_EVENT);
        // 错误 token / 缺失 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> adapter.parse(
                Map.of("Authorization", "Bearer WRONG"), GROUP_EVENT));
        assertThrows(IllegalArgumentException.class, () -> adapter.parse(Map.of(), GROUP_EVENT));
    }

    @Test
    void 缺少messageId被拒绝() {
        NapCatAdapter adapter = new NapCatAdapter(new NapCatProperties());
        assertThrows(IllegalArgumentException.class, () -> adapter.parse(Map.of(),
                "{\"post_type\":\"message\",\"message_type\":\"group\"}"));
    }
}
