package com.omniforge.gateway.email;

import com.omniforge.gateway.ImInboundMessage;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailReplierTest {

    @Test
    void 回复含合规头与AI脚注() throws Exception {
        EmailProperties properties = new EmailProperties();
        properties.setSmtpUser("bot@example.com");
        EmailReplier replier = new EmailReplier(properties);

        MimeMessage reply = replier.buildReply(
                new ImInboundMessage("email", "<original-1@example.com>",
                        "sender@example.com", "问题", false, null),
                "这是回复内容");

        assertEquals("auto-generated", reply.getHeader("Auto-Submitted", null));
        assertEquals("true", reply.getHeader("X-AI-Generated", null));
        assertEquals("<original-1@example.com>", reply.getHeader("In-Reply-To", null));
        assertTrue(reply.getContent().toString().contains("这是回复内容"));
        assertTrue(reply.getContent().toString().contains("本条回复由 AI 自动生成"),
                "正文应包含 AI 生成标识脚注（合规底线）");
    }
}
