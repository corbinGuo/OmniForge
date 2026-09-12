package com.omniforge.gateway.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 邮件接入配置（前缀 {@code omniforge.im.email}）。
 * 未启用（enabled=false，默认）时轮询器不启动。
 */
@ConfigurationProperties(prefix = "omniforge.im.email")
public class EmailProperties {

    /** 邮件接入开关（默认关闭：无凭据时不轮询） */
    private boolean enabled = false;

    // ---- IMAP 收件 ----
    private String imapHost;
    private int imapPort = 993;
    private String imapUser;
    private String imapPassword;
    private String folder = "INBOX";

    // ---- SMTP 回复 ----
    private String smtpHost;
    private int smtpPort = 465;
    private String smtpUser;
    private String smtpPassword;

    // ---- 行为 ----
    /** 轮询间隔（秒，默认 30；指数退避的基础间隔） */
    private int pollIntervalSeconds = 30;

    /** 指数退避封顶（秒，默认 600）：连续失败时轮询间隔翻倍至此上限，成功即复位 */
    private int maxBackoffSeconds = 600;

    /** 附件大小上限（字节，默认 10MB；超限附件跳过并告警） */
    private long maxAttachmentBytes = 10L * 1024 * 1024;

    /** 附件临时目录（流式落盘，不加载进内存） */
    private Path attachmentsDir = defaultConfigDir().resolve("temp").resolve("attachments");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getImapHost() {
        return imapHost;
    }

    public void setImapHost(String imapHost) {
        this.imapHost = imapHost;
    }

    public int getImapPort() {
        return imapPort;
    }

    public void setImapPort(int imapPort) {
        this.imapPort = imapPort;
    }

    public String getImapUser() {
        return imapUser;
    }

    public void setImapUser(String imapUser) {
        this.imapUser = imapUser;
    }

    public String getImapPassword() {
        return imapPassword;
    }

    public void setImapPassword(String imapPassword) {
        this.imapPassword = imapPassword;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public void setSmtpHost(String smtpHost) {
        this.smtpHost = smtpHost;
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public void setSmtpPort(int smtpPort) {
        this.smtpPort = smtpPort;
    }

    public String getSmtpUser() {
        return smtpUser;
    }

    public void setSmtpUser(String smtpUser) {
        this.smtpUser = smtpUser;
    }

    public String getSmtpPassword() {
        return smtpPassword;
    }

    public void setSmtpPassword(String smtpPassword) {
        this.smtpPassword = smtpPassword;
    }

    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public void setPollIntervalSeconds(int pollIntervalSeconds) {
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public int getMaxBackoffSeconds() {
        return maxBackoffSeconds;
    }

    public void setMaxBackoffSeconds(int maxBackoffSeconds) {
        this.maxBackoffSeconds = maxBackoffSeconds;
    }

    public long getMaxAttachmentBytes() {
        return maxAttachmentBytes;
    }

    public void setMaxAttachmentBytes(long maxAttachmentBytes) {
        this.maxAttachmentBytes = maxAttachmentBytes;
    }

    public Path getAttachmentsDir() {
        return attachmentsDir;
    }

    public void setAttachmentsDir(Path attachmentsDir) {
        this.attachmentsDir = attachmentsDir;
    }

    /** 默认配置目录：Linux ~/.omniforge；Windows %APPDATA%\OmniForge（与其他模块一致） */
    private static Path defaultConfigDir() {
        String userHome = System.getProperty("user.home", ".");
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.isBlank()) ? Paths.get(appData) : Paths.get(userHome);
            return base.resolve("OmniForge");
        }
        return Paths.get(userHome, ".omniforge");
    }
}
