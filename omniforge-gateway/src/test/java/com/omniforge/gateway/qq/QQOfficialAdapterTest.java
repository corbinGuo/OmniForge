package com.omniforge.gateway.qq;

import com.omniforge.gateway.ImInboundMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QQ 官方适配器解析测试：单聊/群@/非消息事件/卡片跳过/引用消息/缺字段拒绝。
 */
class QQOfficialAdapterTest {

    private final QQOfficialAdapter adapter = new QQOfficialAdapter();

    private static String c2cEvent(String content) {
        return """
                {"id":"evt-1","op":0,"s":42,"t":"C2C_MESSAGE_CREATE","d":{
                  "id":"ROBOT1.0_abc","author":{"user_openid":"USER_OPENID_1","bot":false},
                  "content":"%s","message_type":0,
                  "message_scene":{"source":"default","ext":["msg_idx=IDX_1"]},
                  "timestamp":"2026-09-01T10:00:00+08:00"}}
                """.formatted(content);
    }

    private static String groupAtEvent(String content) {
        return """
                {"id":"evt-2","op":0,"s":43,"t":"GROUP_AT_MESSAGE_CREATE","d":{
                  "id":"ROBOT1.0_def","author":{"member_openid":"MEMBER_OPENID_9","bot":false},
                  "group_openid":"GROUP_OPENID_7","content":"%s","message_type":0,
                  "timestamp":"2026-09-01T10:01:00+08:00"}}
                """.formatted(content);
    }

    @Test
    void 单聊消息解析() {
        ImInboundMessage message = adapter.parse(Map.of(), c2cEvent("你好，有什么推荐？"));
        assertThat(message).isNotNull();
        assertThat(message.platform()).isEqualTo("qqofficial");
        assertThat(message.messageId()).isEqualTo("ROBOT1.0_abc");
        assertThat(message.from()).isEqualTo("USER_OPENID_1");
        assertThat(message.text()).isEqualTo("你好，有什么推荐？");
        assertThat(message.atMention()).isFalse();
        assertThat(message.platformContext()).containsEntry("kind", "c2c")
                .containsEntry("userOpenid", "USER_OPENID_1")
                .containsEntry("msgId", "ROBOT1.0_abc");
    }

    @Test
    void 群艾特消息解析() {
        ImInboundMessage message = adapter.parse(Map.of(), groupAtEvent("帮我总结一下"));
        assertThat(message).isNotNull();
        assertThat(message.from()).isEqualTo("MEMBER_OPENID_9");
        assertThat(message.text()).isEqualTo("帮我总结一下");
        assertThat(message.atMention()).isTrue();
        assertThat(message.platformContext()).containsEntry("kind", "group")
                .containsEntry("groupOpenid", "GROUP_OPENID_7");
    }

    @Test
    void 非消息事件返回空() {
        // READY / 好友添加 / 心跳等一律 null
        assertThat(adapter.parse(Map.of(), "{\"op\":10,\"d\":{\"heartbeat_interval\":45000}}")).isNull();
        assertThat(adapter.parse(Map.of(), "{\"op\":0,\"t\":\"READY\",\"d\":{\"session_id\":\"s\"}}")).isNull();
        assertThat(adapter.parse(Map.of(), "{\"op\":0,\"t\":\"FRIEND_ADD\",\"d\":{}}")).isNull();
        assertThat(adapter.parse(Map.of(), "{\"op\":1,\"d\":42}")).isNull();
    }

    @Test
    void 卡片消息跳过() {
        // message_type=3（结构化卡片）：content 为摘要，不处理
        String card = c2cEvent("hello").replace("\"message_type\":0", "\"message_type\":3");
        assertThat(adapter.parse(Map.of(), card)).isNull();
    }

    @Test
    void 引用消息取正文() {
        String quoted = c2cEvent("这个建议很有帮助").replace("\"message_type\":0", "\"message_type\":103");
        assertThat(adapter.parse(Map.of(), quoted).text()).isEqualTo("这个建议很有帮助");
    }

    @Test
    void 空文本消息返回空() {
        assertThat(adapter.parse(Map.of(), c2cEvent("   "))).isNull();
    }

    @Test
    void 缺少消息ID拒绝() {
        String noId = c2cEvent("hello").replace("\"id\":\"ROBOT1.0_abc\"", "\"id\":\"\"");
        assertThatThrownBy(() -> adapter.parse(Map.of(), noId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少 id");
    }

    @Test
    void 非JSON载荷拒绝() {
        assertThatThrownBy(() -> adapter.parse(Map.of(), "not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
