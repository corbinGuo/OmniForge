package com.omniforge.app.retention;

import com.omniforge.core.retention.BackupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 升级备份初始化器（DATA_RETENTION A4，app 层）：路径解析 + 触发/跳过分支（不真跑 Spring）。 */
class BackupInitializerTest {

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext contextWith(StandardEnvironment env) {
        ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);
        when(ctx.getEnvironment()).thenReturn(env);
        return ctx;
    }

    private StandardEnvironment env(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return env;
    }

    @Test
    void 库文件存在且无标记时触发备份() throws Exception {
        Path db = tempDir.resolve("omniforge.db");
        Files.writeString(db, "主库");
        StandardEnvironment env = env(Map.of(
                "omniforge.persistence.database-file", db.toString(),
                "omniforge.knowledge.vector-db-file", tempDir.resolve("vectors.db").toString()));

        new BackupInitializer().initialize(contextWith(env));

        try (Stream<Path> s = Files.list(tempDir.resolve("backups"))) {
            assertThat(s.anyMatch(p -> p.getFileName().toString().startsWith("omniforge-"))).isTrue();
        }
    }

    @Test
    void 标记一致时跳过备份() throws Exception {
        Path db = tempDir.resolve("omniforge.db");
        Files.writeString(db, "主库");
        // 模拟上次成功启动已回写标记
        BackupService.writeMarker(tempDir.resolve(BackupService.MARKER_FILE),
                com.omniforge.core.retention.AppSchema.schemaGeneration());
        StandardEnvironment env = env(Map.of(
                "omniforge.persistence.database-file", db.toString()));

        new BackupInitializer().initialize(contextWith(env));

        assertThat(Files.exists(tempDir.resolve("backups"))).isFalse();
    }

    @Test
    void 内存库跳过() {
        StandardEnvironment env = env(Map.of(
                "omniforge.persistence.database-file", ":memory:"));
        new BackupInitializer().initialize(contextWith(env));
        assertThat(Files.exists(tempDir.resolve("backups"))).isFalse();
    }

    @Test
    void 主库不存在跳过() {
        StandardEnvironment env = env(Map.of(
                "omniforge.persistence.database-file", tempDir.resolve("missing.db").toString()));
        new BackupInitializer().initialize(contextWith(env));
        assertThat(Files.exists(tempDir.resolve("backups"))).isFalse();
    }

    @Test
    void 未绑定属性时回退默认库路径而非静默跳过() {
        // U10 真机验收发现：application.yml 不含 omniforge.persistence.* 时（生产常态），
        // 旧实现直接 return → 升级自动备份在真实环境永不触发。回退默认值 = 生产装配同源。
        StandardEnvironment env = env(Map.of("unrelated.key", "x"));
        var result = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("omniforge.persistence", com.omniforge.core.persistence.PersistenceProperties.class);
        assertThat(result.isBound()).isFalse();

        com.omniforge.core.persistence.PersistenceProperties resolved =
                BackupInitializer.resolveProperties(result);
        assertThat(resolved.getDatabaseFile()).endsWith("omniforge.db");
        assertThat(resolved.isInMemory()).isFalse();
    }
}
