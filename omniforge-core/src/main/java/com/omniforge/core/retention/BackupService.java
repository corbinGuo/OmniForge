package com.omniforge.core.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 升级自动备份服务（DATA_RETENTION A4）：
 * 启动时比对 {@link AppSchema#schemaGeneration()} 与落盘标记 {@code .schema-version}，
 * 不一致且主库文件存在 → 复制主库 + 向量库（含 -wal/-shm 侧车）至 {@code <配置目录>/backups/}，
 * 命名含旧标记 + 时间戳，保留最近 3 份自动轮换；标记在上下文成功启动后回写（MarkerWriter）。
 *
 * <p>核心逻辑为静态方法（{@link #backup}）：{@link BackupInitializer}（app 装配层）在
 * Hibernate/Hikari 打开连接前调用；Bean 包装供配置中心「立即备份」按钮复用同一逻辑。</p>
 */
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** 落盘 schema 标记文件名（配置目录下） */
    public static final String MARKER_FILE = ".schema-version";
    /** 自动备份保留份数（设计 §3.2：保留 3 份轮换） */
    public static final int KEEP_BACKUPS = 3;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String DB_PREFIX = "omniforge-";
    private static final String VECTORS_PREFIX = "vectors-";

    private final Path dbFile;
    private final Path vectorsFile;
    private final Path backupDir;

    /** dbFile 为 null 表示内存库/无库（跳过备份；路径落到临时目录避免污染工作区） */
    public BackupService(Path dbFile, Path vectorsFile) {
        this.dbFile = dbFile;
        this.vectorsFile = vectorsFile;
        Path parent = dbFile == null
                ? Path.of(System.getProperty("java.io.tmpdir"), "omniforge-retention-test")
                : dbFile.toAbsolutePath().getParent();
        this.backupDir = parent == null ? Path.of("backups") : parent.resolve("backups");
    }

    /** 备份目录（配置中心 Toast 展示） */
    public Path backupDir() {
        return backupDir;
    }

    /** 落盘标记文件（配置目录下，非备份目录内） */
    public Path markerFile() {
        return backupDir.toAbsolutePath().getParent().resolve(MARKER_FILE);
    }

    /** 立即备份（配置中心按钮；Q4-B 手动兜底）：无条件备份一次，返回备份文件数 */
    public int backupNow() {
        return backup(dbFile, vectorsFile, backupDir, true);
    }

    /** 升级触发备份（启动 initializer / Bean 自检）：仅标记不一致时备份 */
    public int backupIfNeeded() {
        return backup(dbFile, vectorsFile, backupDir, false);
    }

    // ---------- 核心静态逻辑（initializer 与 Bean 共用） ----------

    /**
     * 执行备份（含侧车与轮换）。
     *
     * @param dbFile    主库路径（null / {@code :memory:} / 不存在 → 跳过，首启无库）
     * @param vectors   向量库路径（可为 null/不存在 → 跳过）
     * @param backupDir 备份目录（自动创建）
     * @param force     true=无条件备份；false=仅 schema 标记不一致时备份
     * @return 复制的文件数
     */
    public static int backup(Path dbFile, Path vectorsFile, Path backupDir, boolean force) {
        if (dbFile == null || ":memory:".equals(dbFile.toString())) {
            return 0;
        }
        if (!Files.isRegularFile(dbFile)) {
            return 0; // 首启无库
        }
        Path marker = backupDir.toAbsolutePath().getParent().resolve(MARKER_FILE);
        int currentGen = AppSchema.schemaGeneration();
        int oldGen = readMarker(marker);
        if (!force && oldGen == currentGen) {
            return 0; // 标记一致，无需备份
        }
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            log.warn("备份目录创建失败：{}", e.getMessage());
            return 0;
        }
        String stamp = TS.format(LocalDateTime.now());
        int copied = 0;
        copied += copyWithSidecars(dbFile, backupDir.resolve(DB_PREFIX + oldGen + "-" + stamp + ".db"));
        if (vectorsFile != null && Files.isRegularFile(vectorsFile)) {
            copied += copyWithSidecars(vectorsFile,
                    backupDir.resolve(VECTORS_PREFIX + oldGen + "-" + stamp + ".db"));
        }
        if (copied > 0) {
            log.info("升级备份完成：{} 个文件 → {}", copied, backupDir);
            rotate(backupDir);
        }
        return copied;
    }

    /** 复制单文件（主文件 + 可选 -wal/-shm 侧车），逐个 Files.copy，异常单文件跳过 */
    private static int copyWithSidecars(Path source, Path target) {
        int copied = 0;
        List<Path> sources = new ArrayList<>();
        sources.add(source);
        for (String suffix : new String[]{"-wal", "-shm"}) {
            Path sidecar = Path.of(source.toString() + suffix);
            if (Files.isRegularFile(sidecar)) {
                sources.add(sidecar);
            }
        }
        for (Path s : sources) {
            String name = target.getFileName().toString();
            String suffix = "";
            String path = s.toString();
            if (path.endsWith("-wal")) {
                suffix = "-wal";
            } else if (path.endsWith("-shm")) {
                suffix = "-shm";
            }
            Path t = suffix.isEmpty() ? target
                    : target.resolveSibling(name.replace(".db", "") + suffix + ".db");
            try {
                Files.copy(s, t, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (IOException e) {
                log.warn("备份文件复制失败（已跳过）：{} —— {}", s.getFileName(), e.getMessage());
            }
        }
        return copied;
    }

    /** 按命名时间戳排序保留最近 KEEP_BACKUPS 份主库备份，删更旧（含其侧车） */
    private static void rotate(Path backupDir) {
        List<Path> backups = new ArrayList<>();
        try (var stream = Files.list(backupDir)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(DB_PREFIX) && name.endsWith(".db")
                                && !name.contains("-wal") && !name.contains("-shm");
                    })
                    .forEach(backups::add);
        } catch (IOException e) {
            return;
        }
        backups.sort(Comparator.comparing(p -> p.getFileName().toString()));
        while (backups.size() > KEEP_BACKUPS) {
            Path oldest = backups.remove(0);
            String base = oldest.getFileName().toString().replaceFirst("\\.db$", "");
            deleteQuietly(oldest);
            deleteQuietly(oldest.resolveSibling(base + "-wal.db"));
            deleteQuietly(oldest.resolveSibling(base + "-shm.db"));
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 删除失败不影响主流程
        }
    }

    /** 读标记（缺失/损坏 = -1，表示"旧版本"触发首次备份） */
    static int readMarker(Path marker) {
        if (marker == null || !Files.isRegularFile(marker)) {
            return -1;
        }
        try {
            return Integer.parseInt(Files.readString(marker, StandardCharsets.UTF_8).strip());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 写标记（上下文成功启动后调用，失败仅告警） */
    public static void writeMarker(Path marker, int generation) {
        try {
            if (marker.toAbsolutePath().getParent() != null) {
                Files.createDirectories(marker.toAbsolutePath().getParent());
            }
            Files.writeString(marker, String.valueOf(generation), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("schema 标记写入失败：{}", e.getMessage());
        }
    }
}
