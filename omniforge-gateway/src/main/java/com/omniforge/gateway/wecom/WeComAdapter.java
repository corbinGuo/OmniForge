package com.omniforge.gateway.wecom;

import com.omniforge.gateway.IMessageAdapter;
import com.omniforge.gateway.ImInboundMessage;
import me.chanjar.weixin.cp.bean.message.WxCpXmlMessage;
import me.chanjar.weixin.cp.config.impl.WxCpDefaultConfigImpl;
import me.chanjar.weixin.cp.util.crypto.WxCpCryptUtil;

import java.util.Map;

/**
 * 企业微信适配器（WxJava weixin-java-cp 4.6.0，API 按 4.6.0 源码实证）：
 * 回调消息为加密 XML，经 WxCpCryptUtil（构造参数为配置存储）解密并校验签名。
 */
public class WeComAdapter implements IMessageAdapter {

    public static final String PLATFORM = "wecom";

    private final WeComProperties properties;

    public WeComAdapter(WeComProperties properties) {
        this.properties = properties;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public String displayName() {
        return "企业微信";
    }

    @Override
    public ImInboundMessage parse(Map<String, String> headers, String body) {
        try {
            WxCpDefaultConfigImpl config = new WxCpDefaultConfigImpl();
            config.setToken(properties.getToken());
            config.setAesKey(properties.getAesKey());
            config.setCorpId(properties.getCorpId());
            WxCpCryptUtil cryptUtil = new WxCpCryptUtil(config);
            String plainXml = cryptUtil.decrypt(
                    headers.getOrDefault("msg_signature", ""),
                    headers.getOrDefault("timestamp", ""),
                    headers.getOrDefault("nonce", ""),
                    body);
            WxCpXmlMessage message = WxCpXmlMessage.fromXml(
                    plainXml, String.valueOf(properties.getAgentId()));
            String messageId = message.getMsgId() == null ? "" : String.valueOf(message.getMsgId());
            if (messageId.isBlank()) {
                throw new IllegalArgumentException("企微载荷缺少 MsgId");
            }
            return new ImInboundMessage(PLATFORM, messageId,
                    message.getFromUserName(), message.getContent(), false, null,
                    Map.of("toUser", message.getToUserName() == null ? "" : message.getToUserName()));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("企微消息解析失败：" + e.getMessage(), e);
        }
    }
}
