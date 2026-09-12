package com.omniforge.gateway.email;

import com.omniforge.gateway.ImInboundMessage;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

/**
 * 邮件回复器（Phase 3 Step 3）：SMTP 回复原邮件。
 *
 * <p>合规（需求 9.2）：邮件头 {@code Auto-Submitted: auto-generated} +
 * {@code X-AI-Generated: true}，正文含 AI 生成标识脚注，并带 In-Reply-To 关联原邮件。</p>
 */
public class EmailReplier {

    /** AI 生成标识脚注（合规底线） */
    public static final String AI_FOOTNOTE = "\n\n---\n本条回复由 AI 自动生成。";

    private final EmailProperties properties;

    public EmailReplier(EmailProperties properties) {
        this.properties = properties;
    }

    /** 构建回复（不发送，便于测试合规头与脚注） */
    public MimeMessage buildReply(ImInboundMessage original, String replyText) throws MessagingException {
        MimeMessage reply = new MimeMessage(smtpSession());
        reply.setFrom(new InternetAddress(properties.getSmtpUser()));
        reply.setRecipients(Message.RecipientType.TO, original.from());
        reply.setSubject("Re: AI 回复");
        reply.setHeader("Auto-Submitted", "auto-generated");
        reply.setHeader("X-AI-Generated", "true");
        reply.setHeader("In-Reply-To", original.messageId());
        reply.setText(replyText + AI_FOOTNOTE, "UTF-8");
        reply.saveChanges();
        return reply;
    }

    /** 构建并发送 */
    public void send(ImInboundMessage original, String replyText) throws MessagingException {
        Transport.send(buildReply(original, replyText));
    }

    private Session smtpSession() {
        Properties props = new Properties();
        if (properties.getSmtpHost() != null) {
            props.put("mail.smtp.host", properties.getSmtpHost());
        }
        props.put("mail.smtp.port", String.valueOf(properties.getSmtpPort()));
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.auth", "true");
        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(properties.getSmtpUser(), properties.getSmtpPassword());
            }
        });
    }
}
