package com.omniforge.gateway.email;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 邮件解析工具（包内共享）：仅解析 text/plain 正文；附件流式落盘
 * （边读边计数，超出上限即中止并删除临时文件，不加载进内存）。
 */
final class EmailParser {

    private static final Logger log = LoggerFactory.getLogger(EmailParser.class);

    private EmailParser() {
    }

    /** 提取纯文本正文（只解析 text/plain；无则返回空串） */
    static String extractText(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return content == null ? "" : content.toString();
        }
        if (part.isMimeType("multipart/*")) {
            MimeMultipart multipart = (MimeMultipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                Part child = multipart.getBodyPart(i);
                if (child.isMimeType("text/plain")) {
                    Object content = child.getContent();
                    return content == null ? "" : content.toString();
                }
            }
        }
        return "";
    }

    /** 提取全部附件并流式落盘；单附件失败（含超限）仅跳过并告警，不影响其他附件 */
    static List<EmailAttachment> extractAttachments(Part part, Path attachmentsDir, long maxBytes) {
        List<EmailAttachment> attachments = new ArrayList<>();
        try {
            collectAttachments(part, attachmentsDir, maxBytes, attachments);
        } catch (Exception e) {
            log.warn("附件解析失败：{}", e.getMessage());
        }
        return attachments;
    }

    private static void collectAttachments(Part part, Path dir, long maxBytes,
                                           List<EmailAttachment> out) throws MessagingException, IOException {
        if (part.isMimeType("multipart/*")) {
            MimeMultipart multipart = (MimeMultipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collectAttachments(multipart.getBodyPart(i), dir, maxBytes, out);
            }
            return;
        }
        String fileName = part.getFileName();
        if (fileName == null || fileName.isBlank() || part.isMimeType("text/*")) {
            return;
        }
        try {
            EmailAttachment attachment = saveAttachment(part, dir, maxBytes);
            out.add(attachment);
        } catch (Exception e) {
            log.warn("附件 [{}] 处理失败（已跳过）：{}", fileName, e.getMessage());
        }
    }

    private static EmailAttachment saveAttachment(Part part, Path dir, long maxBytes) throws Exception {
        String safeName = part.getFileName().replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        Path target = dir.resolve(UUID.randomUUID().toString().substring(0, 8) + "-" + safeName);
        long total = 0;
        try (InputStream in = part.getInputStream(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    out.flush();
                    throw new IOException("附件超过大小上限（" + maxBytes + " 字节）");
                }
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            Files.deleteIfExists(target); // 超限/失败：清理临时文件
            throw e;
        }
        return new EmailAttachment(safeName, part.getContentType(), total, target);
    }

    /** 提取发送者（From 首地址） */
    static String extractFrom(Message message) throws MessagingException {
        if (message.getFrom() == null || message.getFrom().length == 0) {
            return "";
        }
        return message.getFrom()[0] instanceof InternetAddress address
                ? address.getAddress() : message.getFrom()[0].toString();
    }

    /** 提取消息 ID（Message-ID 头；缺失时回退为空串，由调用方用 UID 兜底） */
    static String extractMessageId(MimeMessage message) {
        try {
            return message.getMessageID();
        } catch (MessagingException e) {
            return null;
        }
    }
}
