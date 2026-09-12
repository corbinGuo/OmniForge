package com.omniforge.core.gateway.router;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** routing.yml 持久化测试：缺失时生成默认模板，损坏回退默认值，往返一致 */
class RoutingSettingsStoreTest {

    @TempDir
    Path tempDir;

    private final RoutingSettingsStore store = new RoutingSettingsStore();

    @Test
    void 文件缺失时写入默认模板并返回默认值() {
        Path file = tempDir.resolve("routing.yml");
        RoutingSettings settings = store.load(file);
        assertThat(settings.isEnabled()).isTrue();
        assertThat(settings.getSimpleAlias()).isEqualTo("deepseek-chat");
        assertThat(settings.getAdvancedAlias()).isEqualTo("gpt-4o-mini");
        assertThat(Files.exists(file)).as("应生成默认模板供用户编辑").isTrue();
    }

    @Test
    void 保存后读取往返一致() throws Exception {
        Path file = tempDir.resolve("routing.yml");
        RoutingSettings settings = new RoutingSettings(true, "m1", "m2", 0.7,
                java.util.Map.of("tokenLength", 1.0),
                java.util.List.of("分析", "代码"), java.util.List.of("样本"));
        store.save(file, settings);
        RoutingSettings loaded = store.load(file);
        assertThat(loaded.getSimpleAlias()).isEqualTo("m1");
        assertThat(loaded.getAdvancedAlias()).isEqualTo("m2");
        assertThat(loaded.getThreshold()).isEqualTo(0.7);
        assertThat(loaded.getWeights()).containsEntry("tokenLength", 1.0);
        assertThat(loaded.getKeywords()).containsExactly("分析", "代码");
        assertThat(loaded.getExemplars()).containsExactly("样本");
    }

    @Test
    void 文件损坏时回退默认值() throws Exception {
        Path file = tempDir.resolve("routing.yml");
        Files.writeString(file, "{{{ broken");
        assertThat(store.load(file).getSimpleAlias()).isEqualTo("deepseek-chat");
    }

    @Test
    void 字段缺失时归一化为默认值() throws Exception {
        Path file = tempDir.resolve("routing.yml");
        Files.writeString(file, "simpleAlias: only-this\n");
        RoutingSettings loaded = store.load(file);
        assertThat(loaded.getSimpleAlias()).isEqualTo("only-this");
        assertThat(loaded.isEnabled()).isTrue(); // enabled 缺失 → true
        assertThat(loaded.getThreshold()).isEqualTo(0.6);
        assertThat(loaded.getWeights()).isNotEmpty();
    }
}
