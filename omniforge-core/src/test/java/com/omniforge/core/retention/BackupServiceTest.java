package com.omniforge.core.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 升级自动备份（DATA_RETENTION A4）单测：备份产出/轮换/跳过/标记回写。 */
class BackupServiceTest {

    @TempDir
    Path tempDir;

    private Path backupDir() {
        return tempDir.resolve("backups");
    }

    private Path marker() {
        return tempDir.resolve(BackupService.MARKER_FILE);
    }

    private Path dbFile() throws Exception {
        Path db = tempDir.resolve("omniforge.db");
        Files.writeString(db, "主库内容");
        Files.writeString(Path.of(db + "-wal"), "wal 内容");
        return db;
    }

    private Path vectorsFile() throws Exception {
        Path v = tempDir.resolve("vectors.db");
        Files.writeString(v, "向量库内容");
        return v;
    }

    @Test
    void 无标记时首次备份产出主库向量库及侧车() throws Exception {
        Path db = dbFile();
        Path vectors = vectorsFile();
        // 无 .schema-version 标记 → oldGen=-1 → 触发备份
        int copied = BackupService.backup(db, vectors, backupDir(), false);

        assertThat(copied).isEqualTo(3); // 主库 + wal 侧车 + 向量库
        List<Path> files;
        try (Stream<Path> s = Files.list(backupDir())) {
            files = s.toList();
        }
        assertThat(files).anyMatch(p -> p.getFileName().toString().startsWith("omniforge-"));
        assertThat(files).anyMatch(p -> p.getFileName().toString().startsWith("vectors-"));
        assertThat(files).anyMatch(p -> p.getFileName().toString().endsWith("-wal.db"));
    }

    @Test
    void 标记一致时不再备份() throws Exception {
        Path db = dbFile();
        BackupService.writeMarker(marker(), AppSchema.schemaGeneration());
        int copied = BackupService.backup(db, vectorsFile(), backupDir(), false);
        assertThat(copied).isZero();
        assertThat(Files.exists(backupDir())).isFalse();
    }

    @Test
    void 标记不一致且force时无条件备份() throws Exception {
        Path db = dbFile();
        BackupService.writeMarker(marker(), AppSchema.schemaGeneration());
        // force=true 忽略标记 → 仍备份（手动「立即备份」路径）
        int copied = BackupService.backup(db, null, backupDir(), true);
        assertThat(copied).isEqualTo(2); // 主库 + wal
    }

    @Test
    void 内存库或主库不存在跳过() {
        // null 代表内存库/无库（Windows 上 :memory: 无法作路径）
        assertThat(BackupService.backup(null, null, backupDir(), false)).isZero();
        assertThat(BackupService.backup(tempDir.resolve("missing.db"), null, backupDir(), false)).isZero();
    }

    @Test
    void 超过3份自动轮换保留最新() throws Exception {
        Path db = dbFile();
        Files.createDirectories(backupDir());
        // 造 5 份旧备份（命名时间戳递增，排序靠前=更旧）
        String base = "omniforge-1-2026091";
        for (int i = 1; i <= 5; i++) {
            Files.writeString(backupDir().resolve(base + "0" + i + ".db"), "旧备份" + i);
        }
        int copied = BackupService.backup(db, null, backupDir(), true);
        assertThat(copied).isEqualTo(2); // 主库 + wal

        try (Stream<Path> s = Files.list(backupDir())) {
            long count = s.filter(p -> p.getFileName().toString().startsWith("omniforge-")
                            && !p.getFileName().toString().contains("-wal"))
                    .count();
            assertThat(count).isEqualTo(BackupService.KEEP_BACKUPS);
        }
    }

    @Test
    void 标记读写往返() {
        BackupService.writeMarker(marker(), 7);
        assertThat(BackupService.readMarker(marker())).isEqualTo(7);
        assertThat(BackupService.readMarker(null)).isEqualTo(-1);
    }

    @Test
    void 备份文件命名含旧标记与时间戳() throws Exception {
        Path db = dbFile();
        // 旧标记取「当前代际 + 1」：保证 ≠ 当前代（AppSchema 升代时本测试无需再改）
        int oldGen = AppSchema.schemaGeneration() + 1;
        BackupService.writeMarker(marker(), oldGen);
        BackupService.backup(db, null, backupDir(), false);
        try (Stream<Path> s = Files.list(backupDir())) {
            List<Path> files = s.filter(p -> p.getFileName().toString().startsWith("omniforge-")
                            && !p.getFileName().toString().contains("-wal")
                            && !p.getFileName().toString().contains("-shm"))
                    .toList();
            assertThat(files).hasSize(1);
            String name = files.get(0).getFileName().toString();
            // omniforge-<oldGen>-<yyyyMMdd-HHmmss>.db
            assertThat(name).matches("omniforge-" + oldGen + "-\\d{8}-\\d{6}\\.db");
            assertThat(name).contains(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        }
    }

    @Test
    void 标记回写后二次启动不再备份() throws Exception {
        Path db = dbFile();
        BackupService.backup(db, null, backupDir(), false);
        // 模拟成功启动回写标记
        BackupService.writeMarker(marker(), AppSchema.schemaGeneration());
        int second = BackupService.backup(db, null, backupDir(), false);
        assertThat(second).isZero();
        try (Stream<Path> s = Files.list(backupDir())) {
            long count = s.filter(p -> p.getFileName().toString().startsWith("omniforge-")
                            && !p.getFileName().toString().contains("-wal"))
                    .count();
            assertThat(count).isEqualTo(1);
        }
    }
}
