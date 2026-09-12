package com.omniforge.knowledge;

import com.omniforge.knowledge.chunk.SemanticChunker;
import com.omniforge.knowledge.doc.DocumentParser;
import com.omniforge.knowledge.doc.DocumentParsers;
import com.omniforge.knowledge.embedding.DjlEmbeddingEngine;
import com.omniforge.knowledge.embedding.EmbeddingEngine;
import com.omniforge.knowledge.store.InMemoryVectorStore;
import com.omniforge.knowledge.store.SqliteVectorStore;
import com.omniforge.knowledge.store.VectorStore;
import com.omniforge.knowledge.tool.KnowledgeToolProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 知识库自动装配：向量化引擎（DJL/ONNX）、向量存储（SQLite 持久化优先，内存降级）、
 * 解析器、分块器、知识库服务与 knowledge_search 工具提供者。
 */
@AutoConfiguration
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public EmbeddingEngine embeddingEngine(KnowledgeProperties properties) {
        // 决议 #8：配置远程服务（如 Ollama）时绕过本地模型下载
        if (properties.getRemoteUrl() != null && !properties.getRemoteUrl().isBlank()) {
            return new com.omniforge.knowledge.embedding.RemoteEmbeddingEngine(
                    properties.getRemoteUrl(), properties.getRemoteModel());
        }
        return new DjlEmbeddingEngine(properties);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public VectorStore vectorStore(KnowledgeProperties properties) {
        // 2026-09 决议：SQLite 持久化优先（LanceDB 归档，见 docs/LANCE_RETIRED.md）；
        // 数据目录不可写等初始化失败时降级内存存储
        SqliteVectorStore sqlite = new SqliteVectorStore(properties.getVectorDbFile());
        return sqlite.isAvailable() ? sqlite : new InMemoryVectorStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public List<DocumentParser> documentParsers() {
        // 知识库文件格式扩展：统一清单（txt/源码配置/html/rtf/pdf/doc/docx/xls/xlsx/ppt/pptx）
        return DocumentParsers.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public SemanticChunker semanticChunker(KnowledgeProperties properties) {
        return new SemanticChunker(properties.getChunkMaxChars(), properties.getOverlapRatio());
    }

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeService knowledgeService(EmbeddingEngine embeddingEngine, VectorStore vectorStore,
                                             List<DocumentParser> documentParsers, SemanticChunker chunker) {
        KnowledgeService service = new KnowledgeService(embeddingEngine, vectorStore, documentParsers, chunker);
        // 共享服务注册：ServiceLoader 的 KnowledgeToolProvider 与知识库面板使用同一存储
        // （修复双份 InMemoryVectorStore：工具检索不到面板挂载文档的问题）
        KnowledgeToolProvider.setSharedService(service);
        return service;
    }

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeToolProvider knowledgeToolProvider(KnowledgeProperties properties,
                                                       KnowledgeService knowledgeService) {
        return new KnowledgeToolProvider(properties, knowledgeService);
    }
}
