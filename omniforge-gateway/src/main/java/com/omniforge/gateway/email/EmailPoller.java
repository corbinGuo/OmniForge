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
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * IMAP 轮询器（Phase 3 Step 3）：默认 30 秒轮询一次未读邮件。
 *
 * <p>可靠性设计：
 * <ul>
 *   <li>每轮新建 IMAP 连接（连接异常不影响下一轮，天然重连）；</li>
 *   <li>单封邮件解析失败仅告警并继续后续邮件（不阻塞）；</li>
 *   <li>消息被 sink 接受后才标记 SEEN（未处理成功不丢）；</li>
 *   <li>附件经 {@link EmailParser} 流式落盘（不加载进内存）；</li>
 *   <li><b>指数退避（B 级）</b>：连续失败时轮询间隔按 2 的幂递增至
 *       {@code max-backoff-seconds} 封顶（默认 600 秒），成功即复位；
 *       避免服务端不可达/认证失败时以固定短间隔反复重试。</li>
 * </ul>
 */
public class EmailPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EmailPoller.class);

    /** Store 打开器（测试注入用） */
    @FunctionalInterface
    interface StoreSupplier {
        Store open() throws MessagingException;
    }

    private final EmailProperties properties;
    private final Consumer<ImInboundMessage> sink;
    private final StoreSupplier storeSupplier;
    private final ExponentialBackoff backoff;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread pollThread;

    public EmailPoller(EmailProperties properties, Consumer<ImInboundMessage> sink) {
        this(properties, sink, () -> {
            Properties props = new Properties();
            props.put("mail.imaps.ssl.enable", "true");
            return Session.getInstance(props, null).getStore("imaps");
        });
    }

    EmailPoller(EmailProperties properties, Consumer<ImInboundMessage> sink, StoreSupplier storeSupplier) {
        this(properties, sink, storeSupplier, new ExponentialBackoff(
                properties.getPollIntervalSeconds() * 1000L,
                properties.getMaxBackoffSeconds() * 1000L));
    }

    /** 测试可见：注入退避组件（抖动系数 0 保证确定性） */
    EmailPoller(EmailProperties properties, Consumer<ImInboundMessage> sink, StoreSupplier storeSupplier,
                ExponentialBackoff backoff) {
        this.properties = properties;
        this.sink = sink;
        this.storeSupplier = storeSupplier;
        this.backoff = backoff;
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            log.info("邮件接入未启用（omniforge.im.email.enabled=false），轮询器不启动");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        pollThread = Thread.ofVirtual().name("omniforge-email-poller", 0).start(this::pollLoop);
        log.info("邮件轮询已启动：{}@{} 基础间隔 {} 秒（连续失败退避封顶 {} 秒）",
                properties.getImapUser(), properties.getImapHost(),
                properties.getPollIntervalSeconds(), properties.getMaxBackoffSeconds());
    }

    private void pollLoop() {
        while (running.get()) {
            boolean failed = false;
            try {
                pollOnce();
            } catch (Exception e) {
                failed = true;
                log.warn("邮件轮询失败：{}", e.getMessage());
            }
            long delay = nextDelayMillis(failed);
            if (failed) {
                log.warn("邮件轮询进入指数退避：连续失败 {} 次，{} 秒后重试",
                        backoff.consecutiveFailures(), delay / 1000);
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 退避决策（包内可见，便于测试）：失败翻倍，成功复位为基础间隔 */
    long nextDelayMillis(boolean pollFailed) {
        return pollFailed ? backoff.onFailure() : backoff.onSuccess();
    }

    /** 执行一轮轮询（包内可见，便于测试） */
    void pollOnce() throws MessagingException {
        Store store = storeSupplier.open();
        try {
            store.connect(properties.getImapHost(), properties.getImapPort(),
                    properties.getImapUser(), properties.getImapPassword());
            Folder folder = store.getFolder(properties.getFolder());
            folder.open(Folder.READ_WRITE);
            try {
                Message[] unseen = folder.search(
                        new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                log.debug("发现 {} 封未读邮件", unseen.length);
                for (Message message : unseen) {
                    processOne(folder, (MimeMessage) message);
                }
            } finally {
                folder.close(false);
            }
        } finally {
            store.close();
        }
    }

    private void processOne(Folder folder, MimeMessage message) {
        try {
            String messageId = EmailParser.extractMessageId(message);
            String fallbackId = messageId == null || messageId.isBlank()
                    ? "email-uid-" + ((UIDFolder) folder).getUID(message) : messageId;
            String text = EmailParser.extractText(message);
            String from = EmailParser.extractFrom(message);
            EmailParser.extractAttachments(message, properties.getAttachmentsDir(),
                    properties.getMaxAttachmentBytes());
            sink.accept(new ImInboundMessage(EmailAdapter.PLATFORM, fallbackId, from, text, false, null));
            message.setFlag(Flags.Flag.SEEN, true); // 成功后才标记，避免丢失
        } catch (Exception e) {
            log.warn("单封邮件处理失败（跳过，下轮重试）：{}", e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false) && pollThread != null) {
            pollThread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
