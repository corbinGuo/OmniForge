package com.omniforge.knowledge.tool;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolProvider;
import com.omniforge.knowledge.KnowledgeProperties;
import com.omniforge.knowledge.KnowledgeService;
import com.omniforge.knowledge.chunk.SemanticChunker;
import com.omniforge.knowledge.doc.DocumentParser;
import com.omniforge.knowledge.doc.DocumentParsers;
import com.omniforge.knowledge.embedding.DjlEmbeddingEngine;
import com.omniforge.knowledge.embedding.EmbeddingEngine;
import com.omniforge.knowledge.store.InMemoryVectorStore;
import com.omniforge.knowledge.store.SqliteVectorStore;
import com.omniforge.knowledge.store.VectorStore;

import java.util.List;

/**
 * 知识库工具提供者：knowledge_search。
 *
 * <p>ServiceLoader 场景（无 Spring）：按默认配置装配（DJL 引擎 + SQLite/内存存储）；
 * Spring 场景：经 {@code KnowledgeAutoConfiguration} 注入配置化实例。</p>
 */
public class KnowledgeToolProvider implements ToolProvider {

    /** Spring 装配的共享服务：统一 ServiceLoader 与 Spring 场景的向量存储（避免双份存储） */
    private static volatile KnowledgeService sharedService;

    private final List<Tool> tools;

    /** 无参构造（ServiceLoader 使用）：优先复用 Spring 共享服务，否则默认配置自建 */
    public KnowledgeToolProvider() {
        KnowledgeService service = sharedService;
        if (service != null) {
            this.tools = List.of(new KnowledgeTool(service, new KnowledgeProperties().getTopKDefault()));
        } else {
            this.tools = new KnowledgeToolProvider(new KnowledgeProperties()).tools;
        }
    }

    /** 配置化构造（Spring 装配使用） */
    public KnowledgeToolProvider(KnowledgeProperties properties) {
        EmbeddingEngine engine = EmbeddingEngine.discover().orElse(null);
        if (engine == null) {
            engine = new DjlEmbeddingEngine(properties);
        }
        SqliteVectorStore sqlite = new SqliteVectorStore(properties.getVectorDbFile());
        VectorStore store = sqlite.isAvailable() ? sqlite : new InMemoryVectorStore();
        List<DocumentParser> parsers = DocumentParsers.defaults();
        KnowledgeService service = new KnowledgeService(engine, store, parsers,
                new SemanticChunker(properties.getChunkMaxChars(), properties.getOverlapRatio()));
        this.tools = List.of(new KnowledgeTool(service, properties.getTopKDefault()));
    }

    /** 直接注入服务（测试/Spring 装配使用） */
    public KnowledgeToolProvider(KnowledgeProperties properties, KnowledgeService service) {
        this.tools = List.of(new KnowledgeTool(service, properties.getTopKDefault()));
    }

    /** 注册 Spring 共享服务（KnowledgeAutoConfiguration 装配时调用） */
    public static void setSharedService(KnowledgeService service) {
        sharedService = service;
    }

    @Override
    public String name() {
        return "knowledge";
    }

    @Override
    public List<Tool> tools() {
        return tools;
    }
}
