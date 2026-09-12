package com.omniforge.gateway.email;

import com.omniforge.gateway.ImInboundMessage;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailAdapterTest {

    @TempDir
    Path tempDir;

    private static final String PLAIN_EMAIL = """
            From: sender@example.com\r
            To: bot@example.com\r
            Subject: 测试邮件\r
            Message-ID: <test-001@example.com>\r
            MIME-Version: 1.0\r
            Content-Type: text/plain; charset=UTF-8\r
            \r
            这是一封测试邮件正文。\r
            """;

    @Test
    void 解析纯文本邮件() {
        EmailAdapter adapter = new EmailAdapter(new EmailProperties());
        ImInboundMessage message = adapter.parse(Map.of(), PLAIN_EMAIL);

        assertEquals("email", message.platform());
        assertEquals("<test-001@example.com>", message.messageId());
        assertEquals("sender@example.com", message.from());
        assertTrue(message.text().contains("测试邮件正文"));
    }

    @Test
    void 缺少MessageID时拒绝() {
        EmailAdapter adapter = new EmailAdapter(new EmailProperties());
        String raw = PLAIN_EMAIL.replace("Message-ID: <test-001@example.com>\r\n", "");
        assertThrows(IllegalArgumentException.class, () -> adapter.parse(Map.of(), raw));
    }

    @Test
    void 附件流式落盘且内容一致() throws Exception {
        byte[] attachmentBytes = "附件内容attachment".getBytes(StandardCharsets.UTF_8);
        String multipartEmail = """
                From: sender@example.com\r
                Message-ID: <test-002@example.com>\r
                MIME-Version: 1.0\r
                Content-Type: multipart/mixed; boundary="BOUND"\r
                \r
                --BOUND\r
                Content-Type: text/plain; charset=UTF-8\r
                \r
                正文部分。\r
                --BOUND\r
                Content-Type: application/octet-stream; name="data.txt"\r
                Content-Transfer-Encoding: base64\r
                Content-Disposition: attachment; filename="data.txt"\r
                \r
                %s\r
                --BOUND--\r
                """.formatted(Base64.getEncoder().encodeToString(attachmentBytes));

        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties(), null),
                new ByteArrayInputStream(multipartEmail.getBytes(StandardCharsets.UTF_8)));

        List<EmailAttachment> attachments = EmailParser.extractAttachments(
                mime, tempDir, 1024 * 1024);

        assertEquals(1, attachments.size());
        assertEquals("data.txt", attachments.get(0).fileName());
        assertEquals(attachmentBytes.length, attachments.get(0).sizeBytes());
        assertEquals("附件内容attachment",
                Files.readString(attachments.get(0).savedTo(), StandardCharsets.UTF_8));
        assertEquals("正文部分。", EmailParser.extractText(mime).trim());
    }

    @Test
    void 附件超限被跳过且不留临时文件() throws Exception {
        byte[] bigBytes = new byte[2048]; // 2KB
        String multipartEmail = """
                From: sender@example.com\r
                Message-ID: <test-003@example.com>\r
                Content-Type: multipart/mixed; boundary="BOUND"\r
                \r
                --BOUND\r
                Content-Type: text/plain; charset=UTF-8\r
                \r
                正文。\r
                --BOUND\r
                Content-Type: application/octet-stream; name="big.bin"\r
                Content-Transfer-Encoding: base64\r
                Content-Disposition: attachment; filename="big.bin"\r
                \r
                %s\r
                --BOUND--\r
                """.formatted(Base64.getEncoder().encodeToString(bigBytes));

        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties(), null),
                new ByteArrayInputStream(multipartEmail.getBytes(StandardCharsets.UTF_8)));

        List<EmailAttachment> attachments = EmailParser.extractAttachments(mime, tempDir, 1024);

        assertTrue(attachments.isEmpty(), "超限附件应被跳过");
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count(), "超限附件的临时文件应被清理");
        }
    }
}
