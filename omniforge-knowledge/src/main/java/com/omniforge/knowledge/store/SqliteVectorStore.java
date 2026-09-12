package com.omniforge.knowledge.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SQLite 持久化向量存储（需求 4.4 落地实现，2026-09 决议）：单表 {@code vector_embeddings}，
 * 每行即一个切片（id + 元数据 + 384 维 float 向量 BLOB）。
 *
 * <p>检索为暴力余弦：SQL 只负责读出全部向量，相似度在 Java 层计算
 * （{@link VectorUtils#cosineSimilarity}）。桌面单机知识库规模（1~5 万切片）下
 * 扫描性能可接受（384 维 × 5 万 ≈ 77MB 内存，单次全扫约数百毫秒）。</p>
 *
 * <p>背景：LanceDB 官方 Java 高层 SDK 已停止本地嵌入支持（详见 docs/LANCE_RETIRED.md），
 * 故以 SQLite 落地持久化；向量 BLOB 与元数据同表存储，接口不变。</p>
 *
 * <p>并发模型：<b>每操作独立短连接</b>（WAL 模式 + busy_timeout）。2026-09 生产故障复盘：
 * 早期"单连接 + 方法级同步"在企业版高并发面板轮询下，一次原生 sqlite3_step 卡死会
 * 全局互斥所有知识库读写，排队线程再连带占满 PG 连接池（见 docs/）。改为每操作
 * open/close 后天然线程安全、无共享互斥，任何单次卡顿只影响当前调用。</p>
 */
public final class SqliteVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteVectorStore.class);

    private static final String TABLE = "vector_embeddings";

    private static final String COLUMNS = "id, file_name, chunk_index, content, category, vector";

    private final String jdbcUrl;
    private volatile boolean available = false;
    private volatile String availabilityError = "SQLite 向量库未初始化";

    /** 面板高频读缓存（categories / fileRows）：写操作立即失效，读窗口内命中缓存避免反复全表聚合 */
    private static final long LIST_CACHE_TTL_MS = 3000;
    private volatile long listCacheValidUntil = 0;
    private volatile List<String> cachedCategories = List.of();
    private volatile List<VectorStore.FileRow> cachedFileRows = List.of();
    /** single-flight 护栏：同一时刻至多一个线程回源全表聚合，并发突发请求等待后读缓存 */
    private final Object listLock = new Object();

    /**
     * @param dbFile 数据库文件（如 {@code <配置目录>/vectors.db}）；父目录不存在时自动创建
     */
    public SqliteVectorStore(Path dbFile) {
        Objects.requireNonNull(dbFile, "dbFile");
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        try {
            initialize();
            available = true;
        } catch (Throwable e) {
            availabilityError = e.getMessage();
            log.warn("SQLite 向量库初始化失败（将回退内存存储）：{}", e.getMessage());
        }
    }

    /** 建表 + 迁移（用一次性短连接执行，不在实例上保留连接） */
    private void initialize() throws SQLException, IOException {
        Path parent = Path.of(jdbcUrl.substring("jdbc:sqlite:".length())).getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Connection conn = openConnection()) {
            try (Statement statement = conn.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS vector_embeddings (
                            id          TEXT PRIMARY KEY,
                            file_name   TEXT NOT NULL,
                            chunk_index INTEGER NOT NULL,
                            content     TEXT,
                            category    TEXT NOT NULL DEFAULT '',
                            vector      BLOB NOT NULL
                        )""");
                // 迁移（2026-09 知识库分类）：旧库缺 category 列则补列，存量切片回填 ''（未分类），不丢数据
                boolean hasCategory = false;
                try (ResultSet rs = statement.executeQuery("PRAGMA table_info(" + TABLE + ")")) {
                    while (rs.next()) {
                        if ("category".equalsIgnoreCase(rs.getString("name"))) {
                            hasCategory = true;
                            break;
                        }
                    }
                }
                if (!hasCategory) {
                    statement.execute("ALTER TABLE " + TABLE
                            + " ADD COLUMN category TEXT NOT NULL DEFAULT ''");
                    log.info("SQLite 向量库迁移：为已有表补充 category 列（知识库分类），存量切片归入未分类");
                }
            }
        }
    }

    /** 每操作新建短连接（WAL 允许并发读 + 单写；busy_timeout 兜底写互斥） */
    private Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = conn.createStatement()) {
            statement.execute("PRAGMA busy_timeout=30000");
            statement.execute("PRAGMA journal_mode=WAL");
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }

    /** 写操作后调用：使高频读缓存立即失效（下次读取回源全表聚合） */
    private void invalidateListCache() {
        listCacheValidUntil = 0;
    }

    private boolean listCacheFresh() {
        return System.currentTimeMillis() < listCacheValidUntil;
    }

    @Override
    public void add(List<VectorRecord> records) {
        checkAvailable();
        invalidateListCache();
        String sql = "INSERT OR REPLACE INTO " + TABLE
                + " (id, file_name, chunk_index, content, category, vector) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            try {
                for (VectorRecord record : records) {
                    ps.setString(1, record.id());
                    ps.setString(2, stringValue(record.metadata().get("fileName")));
                    ps.setInt(3, intValue(record.metadata().get("chunkIndex")));
                    ps.setString(4, stringValue(record.metadata().get("content")));
                    ps.setString(5, stringValue(record.metadata().get("category")));
                    ps.setBytes(6, toBytes(record.vector()));
                    ps.addBatch();
                }
                ps.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // 回滚失败以原始异常为准
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 向量写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<VectorHit> search(float[] query, int topK) {
        checkAvailable();
        return rankTop(scoreRows(query, null, null), topK);
    }

    @Override
    public List<VectorHit> search(float[] query, int topK, String category) {
        checkAvailable();
        if (category == null || category.isBlank()) {
            return search(query, topK);
        }
        // 分类精确过滤先于余弦打分（候选行数显著变小时避免全表扫描打分）
        return rankTop(scoreRows(query, "category = ?", category), topK);
    }

    /** 读出（可带 WHERE 精确过滤分类）全部向量并余弦打分；whereSql 非空时 categoryValue 作为唯一参数绑定 */
    private List<VectorHit> scoreRows(float[] query, String whereSql, String categoryValue) {
        List<VectorHit> hits = new ArrayList<>();
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE
                + (whereSql == null ? "" : " WHERE " + whereSql);
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            if (categoryValue != null) {
                ps.setString(1, categoryValue);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    float[] vector = fromBytes(rs.getBytes("vector"));
                    double score = VectorUtils.cosineSimilarity(query, vector);
                    hits.add(new VectorHit(rs.getString("id"), score,
                            rowMetadata(rs.getString("file_name"), rs.getInt("chunk_index"),
                                    rs.getString("content"), rs.getString("category"))));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 向量检索失败: " + e.getMessage(), e);
        }
        return hits;
    }

    private static List<VectorHit> rankTop(List<VectorHit> hits, int topK) {
        return hits.stream()
                .sorted(java.util.Comparator.comparingDouble(VectorHit::score).reversed())
                .limit(topK)
                .toList();
    }

    private static Map<String, Object> rowMetadata(String fileName, int chunkIndex, String content,
                                                   String category) {
        return Map.of("fileName", fileName,
                "chunkIndex", chunkIndex,
                "content", content == null ? "" : content,
                "category", category == null ? "" : category);
    }

    @Override
    public int removeByMetadata(String key, String value) {
        checkAvailable();
        String column = switch (key) {
            case "fileName" -> "file_name";
            case "chunkIndex" -> "chunk_index";
            default -> throw new UnsupportedOperationException(
                    "SQLite 向量库不支持按元数据键删除: " + key);
        };
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM " + TABLE + " WHERE " + column + " = ?")) {
            ps.setString(1, value);
            int removed = ps.executeUpdate();
            if (removed > 0) {
                invalidateListCache();
                log.info("按元数据移除向量记录：{}={}，移除 {} 条", key, value, removed);
            }
            return removed;
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 向量删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int updateCategory(String fileName, String category) {
        checkAvailable();
        String target = category == null ? "" : category;
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE " + TABLE + " SET category = ? WHERE file_name = ?")) {
            ps.setString(1, target);
            ps.setString(2, fileName);
            int updated = ps.executeUpdate();
            if (updated > 0) {
                invalidateListCache();
                log.info("更新文档分类：{} → {}，更新 {} 条切片", fileName,
                        target.isBlank() ? "（未分类）" : target, updated);
            }
            return updated;
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 分类更新失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int removeByCategory(String category) {
        checkAvailable();
        String target = category == null ? "" : category;
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM " + TABLE + " WHERE category = ?")) {
            ps.setString(1, target);
            int removed = ps.executeUpdate();
            if (removed > 0) {
                invalidateListCache();
                log.info("按分类删除向量记录：{}，移除 {} 条切片",
                        target.isBlank() ? "（未分类）" : target, removed);
            }
            return removed;
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 分类删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<VectorRecord> all() {
        checkAvailable();
        List<VectorRecord> records = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM " + TABLE);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                records.add(new VectorRecord(
                        rs.getString("id"),
                        fromBytes(rs.getBytes("vector")),
                        rowMetadata(rs.getString("file_name"), rs.getInt("chunk_index"),
                                rs.getString("content"), rs.getString("category"))));
            }
            return records;
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 向量全量列举失败: " + e.getMessage(), e);
        }
    }

    /**
     * 分类去重用 SQL（不走 {@link #all()} 全表扫描：万级切片下毫秒返回）。
     * 语义与 {@link VectorStore#categories()} 默认实现一致：非空、去重、升序。
     * 高频面板读走 3s 短 TTL 缓存（写操作即时失效），避免并发刷新反复聚合。
     */
    @Override
    public List<String> categories() {
        checkAvailable();
        if (listCacheFresh()) {
            return cachedCategories;
        }
        synchronized (listLock) {
            if (listCacheFresh()) {
                return cachedCategories;
            }
            List<String> categories = new ArrayList<>();
            try (Connection connection = openConnection();
                 PreparedStatement ps = connection.prepareStatement(
                         "SELECT DISTINCT category FROM " + TABLE
                                 + " WHERE length(trim(category)) > 0 ORDER BY category");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    categories.add(rs.getString(1));
                }
                cachedCategories = List.copyOf(categories);
                listCacheValidUntil = System.currentTimeMillis() + LIST_CACHE_TTL_MS;
                return cachedCategories;
            } catch (SQLException e) {
                throw new IllegalStateException("SQLite 分类列举失败: " + e.getMessage(), e);
            }
        }
    }

    /** 按文件聚合清单（GROUP BY，毫秒级；语义见 {@link VectorStore#fileRows()}；带 3s 短 TTL + single-flight） */
    @Override
    public List<VectorStore.FileRow> fileRows() {
        checkAvailable();
        if (listCacheFresh()) {
            return cachedFileRows;
        }
        synchronized (listLock) {
            if (listCacheFresh()) {
                return cachedFileRows;
            }
            List<VectorStore.FileRow> rows = new ArrayList<>();
            try (Connection connection = openConnection();
                 PreparedStatement ps = connection.prepareStatement(
                         "SELECT file_name, MAX(category) AS category, COUNT(*) AS chunks,"
                                 + " COALESCE(SUM(length(content)), 0) AS chars"
                                 + " FROM " + TABLE + " GROUP BY file_name ORDER BY file_name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new VectorStore.FileRow(rs.getString("file_name"),
                            rs.getString("category"), rs.getInt("chunks"), rs.getInt("chars")));
                }
                cachedFileRows = List.copyOf(rows);
                listCacheValidUntil = System.currentTimeMillis() + LIST_CACHE_TTL_MS;
                return cachedFileRows;
            } catch (SQLException e) {
                throw new IllegalStateException("SQLite 文档清单聚合失败: " + e.getMessage(), e);
            }
        }
    }

    /** 指定文档切片（WHERE file_name + 顺序读取；语义见 {@link VectorStore#chunksOf(String)}） */
    @Override
    public List<VectorRecord> chunksOf(String fileName) {
        checkAvailable();
        List<VectorRecord> records = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM " + TABLE
                             + " WHERE file_name = ? ORDER BY chunk_index")) {
            ps.setString(1, fileName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new VectorRecord(
                            rs.getString("id"),
                            fromBytes(rs.getBytes("vector")),
                            rowMetadata(rs.getString("file_name"), rs.getInt("chunk_index"),
                                    rs.getString("content"), rs.getString("category"))));
                }
            }
            return records;
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 文档切片读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int clear() {
        checkAvailable();
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement("DELETE FROM " + TABLE)) {
            int removed = ps.executeUpdate();
            if (removed > 0) {
                invalidateListCache();
            }
            return removed;
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 向量清空失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int count() {
        checkAvailable();
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM " + TABLE);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 向量计数失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String description() {
        if (!available) {
            return "SQLite " + jdbcUrl + "（不可用：" + availabilityError + "）";
        }
        return "SQLite " + jdbcUrl + "（" + count() + " 条）";
    }

    @Override
    public void close() {
        // 每操作短连接，无实例级连接需要释放
        available = false;
    }

    private void checkAvailable() {
        if (!available) {
            throw new IllegalStateException("SQLite 向量库不可用：" + availabilityError);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** float[] → 小端序字节数组（BLOB 存储） */
    private static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /** 小端序字节数组 → float[] */
    private static float[] fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
