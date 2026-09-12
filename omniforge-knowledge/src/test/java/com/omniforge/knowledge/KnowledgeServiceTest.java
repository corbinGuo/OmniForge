package com.omniforge.knowledge;

import com.omniforge.knowledge.chunk.SemanticChunker;
import com.omniforge.knowledge.doc.DocumentParser;
import com.omniforge.knowledge.doc.PlainTextParser;
import com.omniforge.knowledge.embedding.EmbeddingEngine;
import com.omniforge.knowledge.store.InMemoryVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void 挂载文档后可按内容检索() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path doc = tempDir.resolve("notes.txt");
        Files.writeString(doc, "OmniForge 是多模型协作平台。向量知识库支持语义检索。"
                + "辩论引擎实现多智能体协作。工具生态兼容 MCP 协议。");

        List<KnowledgeService.ChunkRef> refs = service.addDocument(doc);
        assertTrue(refs.size() >= 1);

        List<KnowledgeService.SearchHit> hits = service.search("知识库", 3);
        assertTrue(hits.size() >= 1);
        assertEquals("notes.txt", hits.get(0).fileName());
    }

    @Test
    void 不支持的文件类型报错() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path doc = tempDir.resolve("data.bin");
        Files.writeString(doc, "x");
        assertThrows(IOException.class, () -> service.addDocument(doc));
    }

    @Test
    void 引擎未就绪时优雅降级() throws IOException {
        EmbeddingEngine unavailable = new EmbeddingEngine() {
            @Override
            public float[] embed(String text) {
                throw new IllegalStateException("not ready");
            }

            @Override
            public boolean isAvailable() {
                return false;
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
        KnowledgeService service = new KnowledgeService(unavailable, new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path doc = tempDir.resolve("notes.txt");
        Files.writeString(doc, "内容");
        assertThrows(IllegalStateException.class, () -> service.addDocument(doc));
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

    // ---------- 文档管理面板（C 级任务 1） ----------

    @Test
    void 挂载进度回调按阶段推进() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path doc = tempDir.resolve("notes.txt");
        Files.writeString(doc, "OmniForge 是多模型协作平台。向量知识库支持语义检索。"
                + "辩论引擎实现多智能体协作。工具生态兼容 MCP 协议。");
        List<KnowledgeService.IndexProgress> progressList = new java.util.ArrayList<>();

        service.addDocument(doc, progressList::add);

        assertTrue(progressList.stream().anyMatch(p -> p.stage() == KnowledgeService.IndexStage.PARSING));
        assertTrue(progressList.stream().anyMatch(p -> p.stage() == KnowledgeService.IndexStage.CHUNKING));
        assertTrue(progressList.stream().anyMatch(p -> p.stage() == KnowledgeService.IndexStage.EMBEDDING));
        KnowledgeService.IndexProgress done = progressList.get(progressList.size() - 1);
        assertEquals(KnowledgeService.IndexStage.DONE, done.stage());
        assertTrue(done.totalChunks() >= 1);
        assertEquals(1.0, done.ratio(), 0.001);
    }

    @Test
    void 文档列表按文件聚合() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path doc1 = tempDir.resolve("a.txt");
        Path doc2 = tempDir.resolve("b.txt");
        Files.writeString(doc1, "第一份文档内容，用于知识库管理面板验证。");
        Files.writeString(doc2, "第二份文档内容，同样用于知识库管理面板验证。");
        service.addDocument(doc1);
        service.addDocument(doc2);

        List<KnowledgeService.DocumentInfo> documents = service.listDocuments();
        assertEquals(2, documents.size());
        assertEquals("a.txt", documents.get(0).fileName());
        assertEquals("b.txt", documents.get(1).fileName());
        assertTrue(documents.get(0).chunks() >= 1);
        assertTrue(documents.get(0).chars() > 0);
    }

    @Test
    void 删除文档移除全部切片且不可检索() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path doc = tempDir.resolve("secret.txt");
        Files.writeString(doc, "OmniForge 是多模型协作平台。向量知识库支持语义检索。"
                + "辩论引擎实现多智能体协作。工具生态兼容 MCP 协议。");
        service.addDocument(doc);
        assertTrue(service.listDocuments().size() == 1);

        int removed = service.removeDocument("secret.txt");
        assertTrue(removed >= 1, "应移除至少一个切片");
        assertTrue(service.listDocuments().isEmpty());
        assertTrue(service.search("知识库", 5).isEmpty(), "删除后不应再检索到");
    }

    @Test
    void 查看文档按切片顺序拼接全文() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path doc = tempDir.resolve("report.md");
        Files.writeString(doc, "OmniForge 是多模型协作平台。向量知识库支持语义检索。"
                + "辩论引擎实现多智能体协作。工具生态兼容 MCP 协议。");
        service.addDocument(doc);

        String content = service.viewDocument("report.md");
        assertTrue(content.contains("OmniForge"), "全文预览应包含正文开头");
        assertTrue(content.contains("MCP"), "全文预览应包含正文结尾");
    }

    @Test
    void 存储不支持管理时抛出明确错误() {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new com.omniforge.knowledge.store.VectorStore() {
            @Override
            public void add(List<com.omniforge.knowledge.store.VectorStore.VectorRecord> records) {
            }

            @Override
            public List<com.omniforge.knowledge.store.VectorStore.VectorHit> search(float[] query, int topK) {
                return List.of();
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String description() {
                return "不支持管理的存储";
            }
        }, List.of(new PlainTextParser()), new SemanticChunker());
        assertThrows(UnsupportedOperationException.class, service::listDocuments);
    }

    // ---------- 知识库分类（2026-09，docs/KNOWLEDGE_CATEGORY_DESIGN.md） ----------

    @Test
    void 自动归类按文件所在目录名() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path dir = Files.createDirectories(tempDir.resolve("财务报告"));
        Path doc = dir.resolve("q1.txt");
        Files.writeString(doc, "第一季度财务报告内容。用于验证自动归类按目录名。");

        service.addDocument(doc);

        KnowledgeService.DocumentInfo info = service.listDocuments().get(0);
        assertEquals("q1.txt", info.fileName());
        assertEquals("财务报告", info.category());
    }

    @Test
    void 显式分类与空串未分类入库() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path cat = tempDir.resolve("a.txt");
        Path un = tempDir.resolve("b.txt");
        Files.writeString(cat, "分类文档：知识库分类支持显式指定分类入库。");
        Files.writeString(un, "未分类文档：空串表示未分类。");
        service.addDocument(cat, "工程");
        service.addDocument(un, "");

        Map<String, String> byName = service.listDocuments().stream()
                .collect(Collectors.toMap(KnowledgeService.DocumentInfo::fileName,
                        KnowledgeService.DocumentInfo::category));
        assertEquals("工程", byName.get("a.txt"));
        assertEquals("", byName.get("b.txt"));
    }

    @Test
    void 按分类过滤检索只返回该分类() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path fin = tempDir.resolve("fin.txt");
        Path tech = tempDir.resolve("tech.txt");
        Files.writeString(fin, "财务知识：报销流程与预算编制说明，用于检索分类过滤验证。");
        Files.writeString(tech, "技术知识：OmniForge 架构与 MCP 协议文档，用于检索分类过滤验证。");
        service.addDocument(fin, "财务");
        service.addDocument(tech, "技术");

        assertTrue(service.search("技术", 5, "技术").stream()
                        .allMatch(h -> "tech.txt".equals(h.fileName())),
                "分类过滤应只返回该分类命中");
        assertTrue(service.search("技术", 5, "财务").stream()
                .allMatch(h -> "fin.txt".equals(h.fileName())));
        assertEquals(2, service.search("技术", 5).size(), "不带分类应全库检索");
    }

    @Test
    void 手动改分类按文件批量生效() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path doc = tempDir.resolve("m.txt");
        Files.writeString(doc, "这份文档会从旧分类移动到新分类，用于验证改分类操作。");
        service.addDocument(doc, "旧类");
        assertEquals("旧类", service.listDocuments().get(0).category());

        service.updateCategory("m.txt", "新类");

        assertEquals("新类", service.listDocuments().get(0).category());
        assertTrue(service.search("分类", 5, "新类").stream()
                .allMatch(h -> "m.txt".equals(h.fileName())));
    }

    @Test
    void 删除整类移除该分类全部文件() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        for (int i = 0; i < 3; i++) {
            Path doc = tempDir.resolve("keep" + i + ".txt");
            Files.writeString(doc, "保留分类的文档" + i + "，用于验证整类删除不影响其他分类。");
            service.addDocument(doc, "保留");
        }
        Path del = tempDir.resolve("del.txt");
        Files.writeString(del, "待删除分类的文档，整类删除时应连同文件一并移除。");
        service.addDocument(del, "删除");

        assertTrue(service.removeByCategory("删除") >= 1);

        assertEquals(3, service.listDocuments().size(), "整类删除后应只剩保留分类的文档");
        assertTrue(service.listDocuments().stream().allMatch(d -> "保留".equals(d.category())));
    }

    @Test
    void 已用分类列表去重并升序() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        for (int i = 0; i < 3; i++) {
            Path doc = tempDir.resolve("d" + i + ".txt");
            Files.writeString(doc, "分类列表去重验证文档 " + i);
            service.addDocument(doc, i % 2 == 0 ? "tech" : "finance");
        }

        assertEquals(List.of("finance", "tech"), service.listCategories());
    }

    // ---------- 文件格式护栏（2026-09-04：10MB / 50 chunk / 友好拒绝） ----------

    @Test
    void 单文件超过10MB拒绝入库() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path big = tempDir.resolve("big.txt");
        Files.write(big, new byte[10 * 1024 * 1024 + 1]);

        IOException ex = assertThrows(IOException.class, () -> service.addDocument(big));
        assertTrue(ex.getMessage().contains("10 MB"), "应提示超上限，实际: " + ex.getMessage());
    }

    @Test
    void 单文件切片超过50仅索引前50() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            content.append("知识库单文件切片护栏验证内容段".repeat(200))
                    .append('。').append(i).append('\n');
        }
        Path doc = tempDir.resolve("many.txt");
        Files.writeString(doc, content.toString());

        List<KnowledgeService.ChunkRef> refs = service.addDocument(doc);

        assertEquals(50, refs.size(), "应截断到单文件 50 切片");
        assertEquals(50, service.listDocuments().get(0).chunks());
    }

    @Test
    void 图片类型给出友好拒绝而非笼统报错() throws IOException {
        KnowledgeService service = new KnowledgeService(fakeEngine(), new InMemoryVectorStore(),
                List.of(new PlainTextParser()), new SemanticChunker());
        Path jpg = tempDir.resolve("photo.jpg");
        Files.write(jpg, new byte[]{1, 2, 3});

        IOException ex = assertThrows(IOException.class, () -> service.addDocument(jpg));
        assertTrue(ex.getMessage().contains("图片"), "图片应给友好提示，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("OCR"), "应提示 OCR 规划中，实际: " + ex.getMessage());
    }
}
