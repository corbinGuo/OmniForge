package com.omniforge.gateway.qq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QQ 配置存储测试：默认值 / 保存热生效 / 环境归一化 / 损坏文件容错。
 */
class QqSettingsStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void 文件缺失返回默认值() {
        QqSettingsStore store = new QqSettingsStore(tempDir);
        QqSettings settings = store.current();
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.webhookEnabled()).isFalse();
        assertThat(settings.environment()).isEqualTo("production");
        assertThat(settings.hasCredentials()).isFalse();
        assertThat(store.file().getFileName().toString()).isEqualTo("qq-im.yml");
    }

    @Test
    void 保存后立即热生效并可重读() throws Exception {
        QqSettingsStore store = new QqSettingsStore(tempDir);
        store.save(new QqSettings(true, "APP_1", "SECRET_1", "sandbox", true, 8443, "/cb", "0.0.0.0", ""));
        assertThat(store.current().enabled()).isTrue();
        assertThat(store.current().appId()).isEqualTo("APP_1");
        assertThat(store.current().environment()).isEqualTo("sandbox");
        // 沙箱环境 base-url 推导
        assertThat(store.current().apiBaseUrl()).isEqualTo("https://sandbox.api.sgroup.qq.com");
        assertThat(store.current().hasCredentials()).isTrue();

        // 新实例读同一文件
        QqSettingsStore reloaded = new QqSettingsStore(tempDir);
        assertThat(reloaded.current().appId()).isEqualTo("APP_1");
        assertThat(reloaded.current().webhookPort()).isEqualTo(8443);
    }

    @Test
    void 环境与空值归一化() {
        QqSettingsStore store = new QqSettingsStore(tempDir);
        QqSettings normalized = new QqSettings(true, " A ", " S ", "UNKNOWN", false, 0, " ", " ", " ").normalize();
        assertThat(normalized.environment()).isEqualTo("production"); // 未知环境回退正式
        assertThat(normalized.appId()).isEqualTo("A"); // 去空白
        assertThat(normalized.webhookPort()).isEqualTo(8080); // 非法端口兜底
        assertThat(normalized.webhookPath()).isEqualTo("/webhook/qq");
        assertThat(normalized.webhookBind()).isEqualTo("127.0.0.1");
        assertThat(normalized.apiBaseUrl()).isEqualTo("https://api.bot.qq.com");
    }

    @Test
    void 损坏文件容错返回默认值() throws Exception {
        Files.writeString(tempDir.resolve("qq-im.yml"), "\t\tnot: [valid: yaml");
        QqSettingsStore store = new QqSettingsStore(tempDir);
        assertThat(store.current().enabled()).isFalse();
        assertThat(store.current().appId()).isEmpty();
    }
}
