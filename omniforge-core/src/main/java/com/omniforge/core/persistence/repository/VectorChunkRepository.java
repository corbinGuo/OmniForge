package com.omniforge.core.persistence.repository;

import com.omniforge.core.persistence.entity.VectorChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 向量切片元数据仓库（向量本体与元数据现统一存于 SQLite 向量库，见 docs/LANCE_RETIRED.md）。 */
public interface VectorChunkRepository extends JpaRepository<VectorChunk, String> {

    List<VectorChunk> findByKnowledgeBaseIdOrderByChunkIndexAsc(String knowledgeBaseId);

    List<VectorChunk> findByKnowledgeBaseIdAndFileName(String knowledgeBaseId, String fileName);
}
