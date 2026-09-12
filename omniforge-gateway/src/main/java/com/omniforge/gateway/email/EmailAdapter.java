package com.omniforge.gateway.email;

import com.omniforge.gateway.IMessageAdapter;
import com.omniforge.gateway.ImInboundMessage;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * 邮件适配器（Phase 3 Step 3）：实现 {@link IMessageAdapter}。
 *
 * <p>邮件为"轮询拉取"型接入（见 {@link EmailPoller}），本适配器的
 * {@link #parse} 用于解析原始 RFC822 文本（测试与未来 webhook 场景）；
 * 邮件无平台签名，headers 参数忽略。</p>
 */
public class EmailAdapter implements IMessageAdapter {

    public static final String PLATFORM = "email";

    private final EmailProperties properties;

    public EmailAdapter(EmailProperties properties) {
        this.properties = properties;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public String displayName() {
        return "邮件";
    }

    @Override
    public ImInboundMessage parse(Map<String, String> headers, String body) {
        try {
            Session session = Session.getInstance(new Properties(), null);
            MimeMessage mime = new MimeMessage(session,
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            String messageId = EmailParser.extractMessageId(mime);
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("邮件缺少 Message-ID");
            }
            String text = EmailParser.extractText(mime);
            String from = EmailParser.extractFrom(mime);
            return new ImInboundMessage(PLATFORM, messageId, from, text, false, null);
        } catch (Exception e) {
            throw new IllegalArgumentException("邮件解析失败：" + e.getMessage(), e);
        }
    }
}
