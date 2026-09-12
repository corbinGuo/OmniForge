package com.omniforge.gateway.email;

import com.omniforge.common.retry.ExponentialBackoff;
import com.omniforge.gateway.ImInboundMessage;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.SearchTerm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailPollerTest {

    @TempDir
    Path tempDir;

    @Test
    void 轮询解析未读邮件并标记已读() throws Exception {
        MimeMessage message = mimeMessage("<m1@example.com>", "sender@example.com", "你好");
        Folder folder = mock(Folder.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(UIDFolder.class));
        when(((UIDFolder) folder).getUID(any())).thenReturn(42L);
        when(folder.search(any(SearchTerm.class))).thenReturn(new Message[]{message});
        Store store = mock(Store.class);
        when(store.getFolder("INBOX")).thenReturn(folder);

        List<ImInboundMessage> received = new ArrayList<>();
        EmailPoller poller = new EmailPoller(properties(), received::add, () -> store);

        poller.pollOnce();

        assertEquals(1, received.size());
        assertEquals("<m1@example.com>", received.get(0).messageId());
        assertEquals("sender@example.com", received.get(0).from());
        assertTrue(received.get(0).text().contains("你好"));
        assertTrue(message.isSet(Flags.Flag.SEEN), "处理成功后应标记 SEEN");
    }

    @Test
    void 单封失败不阻塞后续() throws Exception {
        MimeMessage good = mimeMessage("<m2@example.com>", "a@example.com", "内容二");
        MimeMessage bad = mock(MimeMessage.class);
        // 在解析路径上抛异常（getMessageID 的异常会被解析器兜底为 UID，不构成失败）
        when(bad.isMimeType(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new MessagingException("boom"));
        Folder folder = mock(Folder.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(UIDFolder.class));
        when(((UIDFolder) folder).getUID(any())).thenReturn(42L);
        when(folder.search(any(SearchTerm.class))).thenReturn(new Message[]{bad, good});
        Store store = mock(Store.class);
        when(store.getFolder("INBOX")).thenReturn(folder);

        List<ImInboundMessage> received = new ArrayList<>();
        EmailPoller poller = new EmailPoller(properties(), received::add, () -> store);

        poller.pollOnce(); // bad 抛出异常，good 仍应被处理

        assertEquals(1, received.size());
        assertEquals("<m2@example.com>", received.get(0).messageId());
    }

    @Test
    void 连续失败指数退避成功后复位() {
        // 抖动系数 0：退避决策确定性可断言（基础 30s → 60s → 120s → 240s…）
        ExponentialBackoff backoff = new ExponentialBackoff(30_000, 600_000, 0);
        EmailPoller poller = new EmailPoller(properties(), message -> {
        }, () -> {
            throw new MessagingException("连接失败");
        }, backoff);

        assertEquals(60_000, poller.nextDelayMillis(true), "第 1 次失败：2× 基础间隔");
        assertEquals(120_000, poller.nextDelayMillis(true));
        assertEquals(240_000, poller.nextDelayMillis(true));
        assertEquals(3, backoff.consecutiveFailures());
        assertEquals(30_000, poller.nextDelayMillis(false), "成功即复位基础间隔");
        assertEquals(0, backoff.consecutiveFailures());
    }

    private EmailProperties properties() {
        EmailProperties properties = new EmailProperties();
        properties.setImapHost("localhost");
        properties.setImapUser("test");
        properties.setImapPassword("test");
        properties.setAttachmentsDir(tempDir);
        return properties;
    }

    private static MimeMessage mimeMessage(String messageId, String from, String text) throws Exception {
        String raw = "From: " + from + "\r\nMessage-ID: " + messageId
                + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + text + "\r\n";
        return new MimeMessage(Session.getInstance(new Properties(), null),
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
