package com.omniforge.gateway.wecom;

import com.omniforge.gateway.ImInboundMessage;
import com.omniforge.gateway.ImReplySender;
import me.chanjar.weixin.cp.api.WxCpService;
import me.chanjar.weixin.cp.api.impl.WxCpServiceImpl;
import me.chanjar.weixin.cp.bean.message.WxCpMessage;
import me.chanjar.weixin.cp.config.impl.WxCpDefaultConfigImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 企业微信回复发送器（WxJava 4.6.0）：应用文本消息回复。
 */
public class WeComReplySender implements ImReplySender {

    private static final Logger log = LoggerFactory.getLogger(WeComReplySender.class);

    private final WeComProperties properties;

    public WeComReplySender(WeComProperties properties) {
        this.properties = properties;
    }

    @Override
    public String platform() {
        return WeComAdapter.PLATFORM;
    }

    @Override
    public void send(ImInboundMessage original, String replyText) throws Exception {
        WxCpDefaultConfigImpl config = new WxCpDefaultConfigImpl();
        config.setCorpId(properties.getCorpId());
        config.setCorpSecret(properties.getCorpSecret());
        config.setAgentId(properties.getAgentId());
        WxCpService service = new WxCpServiceImpl();
        service.setWxCpConfigStorage(config);

        WxCpMessage message = new WxCpMessage();
        message.setToUser(original.from());
        message.setMsgType("text");
        message.setContent(replyText);
        message.setAgentId(properties.getAgentId());
        service.getMessageService().send(message);
        log.debug("企微回复已发送：messageId={}", original.messageId());
    }
}
