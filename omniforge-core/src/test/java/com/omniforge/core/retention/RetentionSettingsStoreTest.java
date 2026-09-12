package com.omniforge.core.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 保留策略持久化（DATA_RETENTION A3）单测：默认模板/归一化/缺字段兼容。 */
class RetentionSettingsStoreTest {

    @TempDir
    Path tempDir;

    private final RetentionSettingsStore store = new RetentionSettingsStore();

    @Test
    void 文件缺失返回默认值关闭90_90_180() {
        RetentionSettings settings = store.load(tempDir.resolve("retention.yml"));
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.chatDays()).isEqualTo(90);
        assertThat(settings.logDays()).isEqualTo(90);
        assertThat(settings.auditDays()).isEqualTo(180);
    }

    @Test
    void 保存后读回一致() throws Exception {
        Path file = tempDir.resolve("retention.yml");
        store.save(file, new RetentionSettings(true, 30, 60, 120));
        RetentionSettings loaded = store.load(file);
        assertThat(loaded.enabled()).isTrue();
        assertThat(loaded.chatDays()).isEqualTo(30);
        assertThat(loaded.logDays()).isEqualTo(60);
        assertThat(loaded.auditDays()).isEqualTo(120);
    }

    @Test
    void 损坏文件返回默认值不抛出() throws Exception {
        Path file = tempDir.resolve("retention.yml");
        Files.writeString(file, "{: 不是合法yaml");
        assertThat(store.load(file)).isEqualTo(RetentionSettings.defaults());
    }

    @Test
    void 缺字段null兼容回退默认() throws Exception {
        Path file = tempDir.resolve("retention.yml");
        // 手写缺 auditDays 的配置：只开 enabled + chatDays
        Files.writeString(file, "enabled: true\nchatDays: 45\n");
        RetentionSettings loaded = store.load(file);
        assertThat(loaded.enabled()).isTrue();
        assertThat(loaded.chatDays()).isEqualTo(45);
        assertThat(loaded.logDays()).isEqualTo(90);
        assertThat(loaded.auditDays()).isEqualTo(180);
    }

    @Test
    void 天数钳制至少为1() {
        assertThat(new RetentionSettings(true, 0, -5, 0).chatDays()).isEqualTo(1);
        assertThat(new RetentionSettings(true, 0, -5, 0).logDays()).isEqualTo(1);
        assertThat(new RetentionSettings(true, 0, -5, 0).auditDays()).isEqualTo(1);
    }
}
