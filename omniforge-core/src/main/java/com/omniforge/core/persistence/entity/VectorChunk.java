package com.omniforge.core.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 向量切片（需求 5.1：向量本体与元数据现统一存于 SQLite 向量库
 * {@code vector_embeddings}（2026-09 决议替代 LanceDB，见 docs/LANCE_RETIRED.md）。
 * 本表为 Phase 1 元数据镜像预留，lanceDbRowId 字段保留兼容，当前生产路径由
 * KnowledgeService 经 VectorStore 管理切片）。
 */
@Entity
@Table(name = "vector_chunk")
public class VectorChunk {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "knowledge_base_id")
    private KnowledgeBase knowledgeBase;

    private String fileName;

    private int chunkIndex;

    @Column(columnDefinition = "TEXT")
    private String content;

    private int tokenCount;

    private String lanceDbRowId;

    private LocalDateTime createdAt;

    protected VectorChunk() {
        this.id = UUID.randomUUID().toString();
    }

    public VectorChunk(KnowledgeBase knowledgeBase, String fileName, int chunkIndex,
                       String content, int tokenCount, String lanceDbRowId) {
        this();
        this.knowledgeBase = knowledgeBase;
        this.fileName = fileName;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.tokenCount = tokenCount;
        this.lanceDbRowId = lanceDbRowId;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public KnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    public String getFileName() {
        return fileName;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public String getLanceDbRowId() {
        return lanceDbRowId;
    }

    public void setLanceDbRowId(String lanceDbRowId) {
        this.lanceDbRowId = lanceDbRowId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
