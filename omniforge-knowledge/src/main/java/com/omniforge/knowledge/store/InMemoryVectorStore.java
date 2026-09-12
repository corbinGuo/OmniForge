package com.omniforge.knowledge.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 进程内向量存储（降级方案）：非持久化、暴力余弦检索。
 *
 * <p>仅当 SQLite 持久化存储不可用（如数据目录不可写）时启用，用于演示与测试；
 * 启用时输出告警提示数据不持久。小规模（<1 万切片）检索性能可接受。</p>
 */
public final class InMemoryVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);

    private final ConcurrentHashMap<String, VectorRecord> records = new ConcurrentHashMap<>();

    public InMemoryVectorStore() {
        log.warn("正在使用进程内向量存储（非持久化降级）：SQLite 向量库不可用，数据重启即失");
    }

    @Override
    public void add(List<VectorRecord> recordsToAdd) {
        for (VectorRecord record : recordsToAdd) {
            records.put(record.id(), record);
        }
    }

    @Override
    public List<VectorHit> search(float[] query, int topK) {
        return search(query, topK, null);
    }

    @Override
    public List<VectorHit> search(float[] query, int topK, String category) {
        Stream<VectorRecord> candidates = records.values().stream();
        if (category != null && !category.isBlank()) {
            // 分类精确过滤先于余弦打分
            candidates = candidates.filter(record ->
                    category.equals(Objects.toString(record.metadata().get("category"), "")));
        }
        return candidates
                .map(record -> new VectorHit(record.id(),
                        VectorUtils.cosineSimilarity(query, record.vector()), record.metadata()))
                .sorted(Comparator.comparingDouble(VectorHit::score).reversed())
                .limit(topK)
                .toList();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int removeByMetadata(String key, String value) {
        int before = records.size();
        records.values().removeIf(record -> value == null
                ? record.metadata().get(key) == null
                : value.equals(record.metadata().get(key)));
        int removed = before - records.size();
        if (removed > 0) {
            log.info("按元数据移除向量记录：{}={}，移除 {} 条", key, value, removed);
        }
        return removed;
    }

    @Override
    public int updateCategory(String fileName, String category) {
        String target = category == null ? "" : category;
        int[] updated = {0};
        records.replaceAll((id, record) -> {
            if (fileName.equals(Objects.toString(record.metadata().get("fileName"), ""))) {
                updated[0]++;
                Map<String, Object> meta = new HashMap<>(record.metadata());
                meta.put("category", target);
                return new VectorRecord(id, record.vector(), Map.copyOf(meta));
            }
            return record;
        });
        int count = updated[0];
        if (count > 0) {
            log.info("更新文档分类：{} → {}，更新 {} 条切片", fileName,
                    target.isBlank() ? "（未分类）" : target, count);
        }
        return count;
    }

    @Override
    public int removeByCategory(String category) {
        String target = category == null ? "" : category;
        int before = records.size();
        records.values().removeIf(record ->
                target.equals(Objects.toString(record.metadata().get("category"), "")));
        int removed = before - records.size();
        if (removed > 0) {
            log.info("按分类删除向量记录：{}，移除 {} 条切片",
                    target.isBlank() ? "（未分类）" : target, removed);
        }
        return removed;
    }

    @Override
    public List<VectorRecord> all() {
        return List.copyOf(records.values());
    }

    @Override
    public int clear() {
        int removed = records.size();
        records.clear();
        return removed;
    }

    @Override
    public String description() {
        return "InMemory（非持久化降级，" + records.size() + " 条）";
    }
}
