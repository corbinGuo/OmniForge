package com.omniforge.gateway.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.omniforge.gateway.ImInboundMessage;
import com.omniforge.gateway.ImReplySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 飞书回复发送器（oapi-sdk 2.4.14，API 按 2.4.14 源码实证）：
 * 向消息所在会话发送交互卡片（宽屏文本卡片）。
 */
public class FeishuReplySender implements ImReplySender {

    private static final Logger log = LoggerFactory.getLogger(FeishuReplySender.class);

    private final FeishuProperties properties;

    public FeishuReplySender(FeishuProperties properties) {
        this.properties = properties;
    }

    @Override
    public String platform() {
        return FeishuAdapter.PLATFORM;
    }

    @Override
    public void send(ImInboundMessage original, String replyText) throws Exception {
        Map<String, Object> context = original.platformContext();
        String chatId = context == null || context.get("chatId") == null
                ? null : context.get("chatId").toString();
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalStateException("消息缺少 chat_id，无法回复");
        }
        Client client = Client.newBuilder(properties.getAppId(), properties.getAppSecret()).build();
        CreateMessageReqBody body = CreateMessageReqBody.newBuilder()
                .receiveId(chatId)
                .msgType("interactive")
                .content(buildCardJson(replyText))
                .build();
        CreateMessageReq req = CreateMessageReq.newBuilder()
                .receiveIdType("chat_id")
                .createMessageReqBody(body)
                .build();
        CreateMessageResp resp = client.im().message().create(req);
        if (resp.getCode() != 0) {
            throw new IllegalStateException("飞书发送失败：code=" + resp.getCode()
                    + ", msg=" + resp.getMsg());
        }
        log.debug("飞书卡片已发送：messageId={}", original.messageId());
    }

    /** 简单文本卡片 JSON（转义防注入） */
    static String buildCardJson(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"config\":{\"wide_screen_mode\":true},"
                + "\"elements\":[{\"tag\":\"div\",\"text\":{\"content\":\"" + escaped + "\"}}]}";
    }
}
