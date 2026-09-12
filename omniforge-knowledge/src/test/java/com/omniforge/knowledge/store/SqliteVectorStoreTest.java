package com.omniforge.knowledge.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SqliteVectorStore 测试：临时目录数据库文件，覆盖写入/检索/删除/清空/计数/持久化/降级。
 */
class SqliteVectorStoreTest {

    @TempDir
    Path tempDir;

    /** 各测试持有的存储实例，@AfterEach 统一关闭（释放文件锁，TempDir 才可清理） */
    private SqliteVectorStore store;

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
            store = null;
        }
    }

    private SqliteVectorStore newStore() {
        store = new SqliteVectorStore(tempDir.resolve("vectors.db"));
        return store;
    }

    private static VectorStore.VectorRecord record(String id, String fileName, int chunkIndex,
                                                   String content, float... vector) {
        return new VectorStore.VectorRecord(id, vector,
                Map.of("fileName", fileName, "chunkIndex", chunkIndex, "content", content));
    }

    private static VectorStore.VectorRecord recordC(String id, String fileName, String category,
                                                    int chunkIndex, String content, float... vector) {
        return new VectorStore.VectorRecord(id, vector,
                Map.of("fileName", fileName, "chunkIndex", chunkIndex,
                        "content", content, "category", category));
    }

    @Test
    void 写入后按余弦检索取最相近() {
        SqliteVectorStore store = newStore();
        assertThat(store.isAvailable()).isTrue();

        // a 与 c 正交，b 位于两者之间；query 与 b 余弦最高
        store.add(List.of(
                record("a", "doc1.txt", 0, "alpha", 1f, 0f),
                record("b", "doc1.txt", 1, "beta", 0.71f, 0.71f),
                record("c", "doc2.txt", 0, "gamma", 0f, 1f)));

        List<VectorStore.VectorHit> hits = store.search(new float[]{0.6f, 0.8f}, 2);
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).id()).isEqualTo("b");
        assertThat(hits.get(0).score()).isGreaterThan(0.98);
        assertThat(hits.get(0).metadata()).containsEntry("fileName", "doc1.txt")
                .containsEntry("chunkIndex", 1)
                .containsEntry("content", "beta");
        assertThat(hits.get(1).id()).isEqualTo("c");
        assertThat(hits.get(0).score()).isGreaterThanOrEqualTo(hits.get(1).score());
    }

    @Test
    void 全量列举还原元数据() {
        SqliteVectorStore store = newStore();
        store.add(List.of(record("a", "doc1.txt", 0, "内容甲", 1f, 0f)));

        List<VectorStore.VectorRecord> all = store.all();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).metadata()).containsEntry("fileName", "doc1.txt")
                .containsEntry("chunkIndex", 0)
                .containsEntry("content", "内容甲");
        assertThat(all.get(0).vector()).containsExactly(1f, 0f);
    }

    @Test
    void 按文件名删除全部切片() {
        SqliteVectorStore store = newStore();
        store.add(List.of(
                record("a", "doc1.txt", 0, "x", 1f, 0f),
                record("b", "doc1.txt", 1, "y", 0f, 1f),
                record("c", "doc2.txt", 0, "z", 1f, 1f)));

        assertThat(store.removeByMetadata("fileName", "doc1.txt")).isEqualTo(2);
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.all()).extracting(r -> r.metadata().get("fileName")).containsExactly("doc2.txt");
    }

    @Test
    void 不支持元数据键时拒绝删除() {
        SqliteVectorStore store = newStore();
        assertThatThrownBy(() -> store.removeByMetadata("unknownKey", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 清空与计数() {
        SqliteVectorStore store = newStore();
        assertThat(store.count()).isZero();
        store.add(List.of(record("a", "doc1.txt", 0, "x", 1f)));
        store.add(List.of(record("b", "doc2.txt", 0, "y", 2f)));
        assertThat(store.count()).isEqualTo(2);

        assertThat(store.clear()).isEqualTo(2);
        assertThat(store.count()).isZero();
        assertThat(store.all()).isEmpty();
    }

    @Test
    void 关闭后重开仍可读到数据() {
        Path db = tempDir.resolve("persist.db");
        SqliteVectorStore first = new SqliteVectorStore(db);
        first.add(List.of(record("a", "doc1.txt", 0, "持久化", 1f, 0f)));
        first.close();
        assertThat(first.isAvailable()).isFalse();

        SqliteVectorStore reopened = new SqliteVectorStore(db);
        assertThat(reopened.isAvailable()).isTrue();
        assertThat(reopened.count()).isEqualTo(1);
        assertThat(reopened.search(new float[]{1f, 0f}, 1).get(0).id()).isEqualTo("a");
        reopened.close();
    }

    @Test
    void 数据目录不可用时降级为不可用状态() throws Exception {
        // 父路径是已存在的文件而非目录 → createDirectories 失败 → available=false
        Path blockingFile = tempDir.resolve("blocker.txt");
        Files.writeString(blockingFile, "block");
        SqliteVectorStore store = new SqliteVectorStore(blockingFile.resolve("vectors.db"));
        assertThat(store.isAvailable()).isFalse();
        assertThat(store.description()).contains("不可用");
        assertThatThrownBy(() -> store.add(List.of(record("a", "d", 0, "x", 1f))))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- 知识库分类（2026-09，docs/KNOWLEDGE_CATEGORY_DESIGN.md） ----------

    @Test
    void 写入与还原携带分类() {
        SqliteVectorStore store = newStore();
        store.add(List.of(recordC("a", "doc1.txt", "财务", 0, "预算", 1f, 0f)));

        assertThat(store.all().get(0).metadata()).containsEntry("category", "财务");
    }

    @Test
    void 旧表缺category列启动自动迁移() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE vector_embeddings (id TEXT PRIMARY KEY, file_name TEXT NOT NULL, "
                    + "chunk_index INTEGER NOT NULL, content TEXT, vector BLOB NOT NULL)");
            st.execute("INSERT INTO vector_embeddings (id, file_name, chunk_index, content, vector) "
                    + "VALUES ('old1', 'legacy.txt', 0, '旧数据', x'0000803F0000803F')");
        }
        SqliteVectorStore migrated = new SqliteVectorStore(db);
        try {
            assertThat(migrated.isAvailable()).isTrue();
            assertThat(migrated.all()).hasSize(1);
            VectorStore.VectorRecord row = migrated.all().get(0);
            assertThat(row.metadata()).containsEntry("fileName", "legacy.txt")
                    .containsEntry("category", "");
            // 迁移后新列可用：分类写入 / 改分类 / 整类删除均正常
            migrated.add(List.of(recordC("n1", "new.txt", "财务", 0, "新文档", 1f, 1f)));
            assertThat(migrated.updateCategory("legacy.txt", "历史")).isEqualTo(1);
            assertThat(migrated.all()).filteredOn(r -> "legacy.txt".equals(r.metadata().get("fileName")))
                    .allMatch(r -> "历史".equals(r.metadata().get("category")));
            assertThat(migrated.removeByCategory("历史")).isEqualTo(1);
            assertThat(migrated.all()).extracting(r -> r.metadata().get("fileName"))
                    .containsExactly("new.txt");
        } finally {
            migrated.close();
        }
    }

    @Test
    void 按分类过滤检索只返回该分类() {
        SqliteVectorStore store = newStore();
        store.add(List.of(
                recordC("a1", "docA.txt", "甲", 0, "aa", 1f, 0f),
                recordC("a2", "docA.txt", "甲", 1, "ab", 1f, 0f),
                recordC("b1", "docB.txt", "乙", 0, "bb", 0f, 1f)));

        List<VectorStore.VectorHit> hits = store.search(new float[]{1f, 0f}, 5, "甲");
        assertThat(hits).hasSize(2);
        assertThat(hits).allSatisfy(h -> assertThat(h.metadata()).containsEntry("category", "甲"));
        assertThat(store.search(new float[]{1f, 0f}, 5, "乙")).hasSize(1);
        assertThat(store.search(new float[]{1f, 0f}, 5)).hasSize(3);
        assertThat(store.search(new float[]{1f, 0f}, 5, "")).hasSize(3);
    }

    @Test
    void 改分类按文件名批量更新() {
        SqliteVectorStore store = newStore();
        store.add(List.of(
                recordC("a", "doc1.txt", "甲", 0, "x", 1f, 0f),
                recordC("b", "doc1.txt", "甲", 1, "y", 0f, 1f),
                recordC("c", "doc2.txt", "乙", 0, "z", 1f, 1f)));

        assertThat(store.updateCategory("doc1.txt", "丙")).isEqualTo(2);

        assertThat(store.all()).filteredOn(r -> "doc1.txt".equals(r.metadata().get("fileName")))
                .allMatch(r -> "丙".equals(r.metadata().get("category")));
        assertThat(store.all()).filteredOn(r -> "doc2.txt".equals(r.metadata().get("fileName")))
                .allMatch(r -> "乙".equals(r.metadata().get("category")));
    }

    @Test
    void 按分类整类删除() {
        SqliteVectorStore store = newStore();
        store.add(List.of(
                recordC("a1", "x1.txt", "甲", 0, "x", 1f, 0f),
                recordC("a2", "x2.txt", "甲", 0, "y", 0f, 1f),
                recordC("b1", "y1.txt", "乙", 0, "z", 1f, 1f)));

        assertThat(store.removeByCategory("甲")).isEqualTo(2);
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.all()).extracting(r -> r.metadata().get("fileName")).containsExactly("y1.txt");
    }
}
