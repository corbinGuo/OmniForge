package com.omniforge.knowledge.tool;

import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import com.omniforge.knowledge.KnowledgeService;
import com.omniforge.knowledge.chunk.SemanticChunker;
import com.omniforge.knowledge.doc.PlainTextParser;
import com.omniforge.knowledge.embedding.EmbeddingEngine;
import com.omniforge.knowledge.store.InMemoryVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeToolTest {

    @TempDir
    Path tempDir;

    @Test
    void 引擎未就绪时返回明确指引() {
        KnowledgeService service = new KnowledgeService(null, new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        KnowledgeTool tool = new KnowledgeTool(service, 5);

        ToolResult result = tool.execute(ToolRequest.of(Map.of("query", "测试")));

        assertEquals(ToolResult.ToolStatus.FAILED, result.status());
        assertTrue(result.error().contains("Embedding 引擎未就绪"), "应包含引擎未就绪的指引");
    }

    @Test
    void 检索成功返回格式化片段() throws Exception {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path doc = tempDir.resolve("faq.txt");
        Files.writeString(doc, "如何安装 OmniForge？下载安装包后双击运行。");
        service.addDocument(doc);
        KnowledgeTool tool = new KnowledgeTool(service, 5);

        ToolResult result = tool.execute(ToolRequest.of(Map.of("query", "安装")));

        assertEquals(ToolResult.ToolStatus.SUCCESS, result.status());
        assertTrue(result.output().contains("faq.txt"), "结果应包含文件名");
        assertTrue(result.output().contains("安装"), "结果应包含命中内容");
    }

    @Test
    void 缺少query参数报错() {
        KnowledgeService service = new KnowledgeService(null, new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        KnowledgeTool tool = new KnowledgeTool(service, 5);

        ToolResult result = tool.execute(ToolRequest.of(Map.of()));

        assertEquals(ToolResult.ToolStatus.FAILED, result.status());
    }

    @Test
    void 带category参数检索只返回该分类() throws Exception {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path fin = tempDir.resolve("fin.txt");
        Path tech = tempDir.resolve("tech.txt");
        Files.writeString(fin, "财务文档：报销流程与预算编制说明。");
        Files.writeString(tech, "技术文档：OmniForge 架构与 MCP 协议说明。");
        service.addDocument(fin, "财务");
        service.addDocument(tech, "技术");
        KnowledgeTool tool = new KnowledgeTool(service, 5);

        ToolResult result = tool.execute(ToolRequest.of(Map.of("query", "协议", "category", "技术")));

        assertEquals(ToolResult.ToolStatus.SUCCESS, result.status());
        assertTrue(result.output().contains("tech.txt"), "结果应来自指定分类");
        assertTrue(!result.output().contains("fin.txt"), "结果不应包含其他分类的文件");
    }

    private static EmbeddingEngine fakeEngine() {
        return new EmbeddingEngine() {
            @Override
            public float[] embed(String text) {
                return new float[]{text.length() % 10 + 1};
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String description() {
                return "fake";
            }

            @Override
            public int dimensions() {
                return 1;
            }
        };
    }
}
