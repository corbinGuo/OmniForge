package com.omniforge.ui.theme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** ui.yml 持久化测试：缺失/损坏回退默认值，保存-读取往返一致 */
class UiSettingsStoreTest {

    @TempDir
    Path tempDir;

    private final UiSettingsStore store = new UiSettingsStore();

    @Test
    void 文件缺失时返回默认暗色() {
        assertThat(store.load(tempDir.resolve("ui.yml")).theme()).isEqualTo(UiSettings.THEME_DARK);
    }

    @Test
    void 保存后读取往返一致() throws Exception {
        Path file = tempDir.resolve("ui.yml");
        store.save(file, new UiSettings(UiSettings.THEME_LIGHT));
        assertThat(store.load(file).theme()).isEqualTo(UiSettings.THEME_LIGHT);
    }

    @Test
    void 保存自动创建父目录() throws Exception {
        Path file = tempDir.resolve("nested").resolve("dir").resolve("ui.yml");
        store.save(file, new UiSettings(UiSettings.THEME_LIGHT));
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void 文件损坏时回退默认暗色() throws Exception {
        Path file = tempDir.resolve("ui.yml");
        Files.writeString(file, "{{{ not yaml");
        assertThat(store.load(file).theme()).isEqualTo(UiSettings.THEME_DARK);
    }

    @Test
    void 未知主题值经归一化回退暗色() throws Exception {
        Path file = tempDir.resolve("ui.yml");
        Files.writeString(file, "theme: neon");
        assertThat(store.load(file).theme()).isEqualTo(UiSettings.THEME_DARK);
    }
}
