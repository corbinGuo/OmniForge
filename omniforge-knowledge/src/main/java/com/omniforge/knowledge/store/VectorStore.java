package com.omniforge.knowledge.store;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * 向量存储 SPI（需求 4.4：SQLite 为持久化实现，见 docs/LANCE_RETIRED.md；内存实现为降级方案）。
 */
public interface VectorStore extends AutoCloseable {

    /** 批量写入向量记录（含元数据） */
    void add(List<VectorRecord> records);

    /** 语义检索 topK（余弦相似度降序） */
    List<VectorHit> search(float[] query, int topK);

    /**
     * 语义检索（知识库分类，2026-09，详见 docs/KNOWLEDGE_CATEGORY_DESIGN.md）：
     * 在余弦打分前按 {@code category} 精确过滤候选（区分大小写）。
     *
     * <p>默认实现按全量列举后过滤（性能较差）；SQLite/内存实现重写为打分前过滤。
     * 实现方也可直接委托 {@link #search(float[], int)}（忽略过滤），语义由
     * {@code KnowledgeService} 保证——此处默认实现提供正确兜底。</p>
     *
     * @param category 精确分类；null 或空串 = 全库检索
     */
    default List<VectorHit> search(float[] query, int topK, String category) {
        if (category == null || category.isBlank()) {
            return search(query, topK);
        }
        return all().stream()
                .filter(record -> category.equals(Objects.toString(record.metadata().get("category"), "")))
                .map(record -> new VectorHit(record.id(),
                        VectorUtils.cosineSimilarity(query, record.vector()), record.metadata()))
                .sorted(Comparator.comparingDouble(VectorHit::score).reversed())
                .limit(topK)
                .toList();
    }

    /** 存储是否可用 */
    boolean isAvailable();

    /** 存储描述（如 "SQLite <配置目录>/vectors.db（N 条）" / "InMemory（非持久化降级）"） */
    String description();

    /**
     * 移除元数据中指定键值匹配的全部记录（文档管理面板"删除文档"）。
     *
     * @return 实际移除的记录数
     * @throws UnsupportedOperationException 存储实现未支持时抛出
     */
    default int removeByMetadata(String key, String value) {
        throw new UnsupportedOperationException("当前向量存储不支持按元数据删除: " + description());
    }

    /**
     * 更新某文档（file_name 匹配）全部切片的分类（知识库面板"改分类"）。
     *
     * @return 实际更新切片数
     * @throws UnsupportedOperationException 存储实现未支持时抛出
     */
    default int updateCategory(String fileName, String category) {
        throw new UnsupportedOperationException("当前向量存储不支持修改文档分类: " + description());
    }

    /**
     * 移除指定分类下全部文件及其切片（知识库面板"删除整类"）。
     *
     * @return 实际移除切片数
     * @throws UnsupportedOperationException 存储实现未支持时抛出
     */
    default int removeByCategory(String category) {
        throw new UnsupportedOperationException("当前向量存储不支持按分类删除: " + description());
    }

    /**
     * 全部记录快照（文档管理面板"文档列表"用；小规模场景）。
     *
     * @throws UnsupportedOperationException 存储实现未支持时抛出
     */
    default List<VectorRecord> all() {
        throw new UnsupportedOperationException("当前向量存储不支持全量列举: " + description());
    }

    /**
     * 全部已用分类（去重、非空、升序，知识库面板分类下拉用；不含"未分类"）。
     *
     * <p>默认实现基于 {@link #all()} 全量快照过滤（数据量大时性能差）；
     * SQLite 实现重写为 {@code SELECT DISTINCT category}，毫秒级返回。</p>
     */
    default List<String> categories() {
        return all().stream()
                .map(record -> Objects.toString(record.metadata().get("category"), ""))
                .filter(category -> !category.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 按文件聚合清单（file_name 升序；知识库管理面板"文档列表"用）。
     *
     * <p>默认实现基于 {@link #all()} 全量快照按文件名归并（数据量大时性能差）；
     * SQLite 实现重写为 {@code GROUP BY file_name} 聚合，毫秒级返回。</p>
     */
    default List<FileRow> fileRows() {
        Map<String, FileRow> rows = new java.util.TreeMap<>();
        for (VectorRecord record : all()) {
            String fileName = Objects.toString(record.metadata().get("fileName"), "");
            if (fileName.isBlank()) {
                continue;
            }
            FileRow prev = rows.get(fileName);
            String category = Objects.toString(record.metadata().get("category"), "");
            if (prev == null) {
                rows.put(fileName, new FileRow(fileName, category, 1,
                        Objects.toString(record.metadata().get("content"), "").length()));
            } else {
                rows.put(fileName, new FileRow(fileName, category,
                        prev.chunks() + 1,
                        prev.chars() + Objects.toString(record.metadata().get("content"), "").length()));
            }
        }
        return List.copyOf(rows.values());
    }

    /**
     * 指定文档的全部切片（按 chunk_index 升序；知识库面板"查看全文"用）。
     *
     * <p>默认实现基于 {@link #all()} 全量快照过滤（数据量大时性能差）；
     * SQLite 实现重写为 {@code WHERE file_name = ? ORDER BY chunk_index}。</p>
     */
    default List<VectorRecord> chunksOf(String fileName) {
        return all().stream()
                .filter(record -> fileName.equals(Objects.toString(record.metadata().get("fileName"), "")))
                .sorted(Comparator.comparingInt(record -> record.metadata().get("chunkIndex") instanceof Number n
                        ? n.intValue() : 0))
                .toList();
    }

    /**
     * 清空全部记录（"清空知识库"）。
     *
     * @return 移除的记录数
     * @throws UnsupportedOperationException 存储实现未支持时抛出
     */
    default int clear() {
        throw new UnsupportedOperationException("当前向量存储不支持清空: " + description());
    }

    /** 记录总数 */
    default int count() {
        return all().size();
    }

    @Override
    default void close() {
    }

    /** 经 ServiceLoader 发现外部存储实现（扩展点；生产装配走 SQLite 优先，见 KnowledgeAutoConfiguration） */
    static Optional<VectorStore> discover() {
        return ServiceLoader.load(VectorStore.class).findFirst();
    }

    /** 向量记录 */
    record VectorRecord(String id, float[] vector, Map<String, Object> metadata) {
    }

    /** 检索命中 */
    record VectorHit(String id, double score, Map<String, Object> metadata) {
    }

    /** 文档级聚合行（按 file_name 归并：分类、切片数、字符数；知识库面板"文档列表"用） */
    record FileRow(String fileName, String category, int chunks, int chars) {
    }
}
