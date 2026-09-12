package com.omniforge.knowledge;

import com.omniforge.knowledge.chunk.SemanticChunker;
import com.omniforge.knowledge.chunk.TextChunk;
import com.omniforge.knowledge.doc.DocumentParser;
import com.omniforge.knowledge.embedding.EmbeddingEngine;
import com.omniforge.knowledge.store.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 知识库服务（需求 4.4）：文档挂载（解析→分块→向量化→入库）与语义检索。
 *
 * <p>切片与向量经 {@link VectorStore} 持久化（SQLite 单表，2026-09 决议，
 * 详见 docs/LANCE_RETIRED.md）；本服务负责解析/分块/向量化与存储编排。</p>
 */
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /**
     * 单文件解析上限（护栏，2026-09-04 设计确认：10 MB）。
     * 超过不读取（避免超大文件拖垮桌面解析/内存）。
     */
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /** 单文件索引切片上限（护栏，2026-09-04 设计确认：50 chunk），超出仅索引前 50 片 */
    static final int MAX_CHUNKS_PER_FILE = 50;

    /** 无文本层、暂不支持入库的图片类型（OCR 规划中，UI/工具层给出友好提示） */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff");

    private final EmbeddingEngine embeddingEngine;
    private final VectorStore vectorStore;
    private final List<DocumentParser> parsers;
    private final SemanticChunker chunker;

    public KnowledgeService(EmbeddingEngine embeddingEngine, VectorStore vectorStore,
                            List<DocumentParser> parsers, SemanticChunker chunker) {
        this.embeddingEngine = embeddingEngine; // 可为 null（引擎未安装时优雅降级）
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.parsers = parsers == null ? List.of() : List.copyOf(parsers);
        this.chunker = Objects.requireNonNull(chunker, "chunker");
    }

    /** 挂载文档：解析 → 语义分块（10% 重叠）→ 向量化 → 入库；返回切片清单 */
    public List<ChunkRef> addDocument(Path file) throws IOException {
        return addDocument(file, null, progress -> {
        });
    }

    /**
     * 挂载文档（带进度回调，知识库面板进度显示用）：
     * 解析 → 语义分块（10% 重叠）→ 逐块向量化（回调进度）→ 入库。
     */
    public List<ChunkRef> addDocument(Path file, Consumer<IndexProgress> onProgress) throws IOException {
        return addDocument(file, null, onProgress);
    }

    /** 挂载文档并显式指定分类（分类为 null 时按 {@link #addDocument(Path, String)} 规则自动归类） */
    public List<ChunkRef> addDocument(Path file, String category) throws IOException {
        return addDocument(file, category, progress -> {
        });
    }

    /**
     * 挂载文档（知识库分类，2026-09，详见 docs/KNOWLEDGE_CATEGORY_DESIGN.md）：
     * 解析 → 语义分块（10% 重叠）→ 逐块向量化（回调进度）→ 入库。
     *
     * @param category 文档分类（写入全部切片的 metadata）；null = 自动按文件所在目录名归类，
     *                 空串 = 归入"未分类"
     */
    public List<ChunkRef> addDocument(Path file, String category, Consumer<IndexProgress> onProgress)
            throws IOException {
        Objects.requireNonNull(file, "file");
        Consumer<IndexProgress> progress = onProgress == null ? p -> {
        } : onProgress;
        String fileName = file.getFileName().toString();
        String docCategory = resolveCategory(file, category);
        // 护栏：单文件 ≤ 10MB（先查尺寸再解析，超大文件直接拒绝）
        long fileBytes = -1;
        try {
            fileBytes = Files.size(file);
        } catch (IOException ignored) {
            // 尺寸不可读时交给解析器自行报错
        }
        if (fileBytes > MAX_FILE_BYTES) {
            throw new IOException("文件超过单文件解析上限 10 MB: " + fileName
                    + "（约 " + (fileBytes / 1024 / 1024) + " MB）");
        }
        EmbeddingEngine engine = requireEngine();
        DocumentParser parser = parsers.stream()
                .filter(p -> p.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new IOException(unsupportedTypeMessage(fileName)));
        progress.accept(new IndexProgress(fileName, IndexStage.PARSING, 0, 0));
        String text = parser.parse(file);
        if (text == null || text.isBlank()) {
            throw new IOException("文档无文本内容（可能是扫描版 PDF 或图片）: " + fileName);
        }
        progress.accept(new IndexProgress(fileName, IndexStage.CHUNKING, 0, 0));
        List<TextChunk> chunks = chunker.chunk(text);
        // 护栏：单文件 ≤ 50 chunk（超出仅索引前 50 片）
        if (chunks.size() > MAX_CHUNKS_PER_FILE) {
            log.info("文档「{}」切片 {} 片超过单文件上限 {}，仅索引前 {} 片（护栏）",
                    fileName, chunks.size(), MAX_CHUNKS_PER_FILE, MAX_CHUNKS_PER_FILE);
            chunks = new ArrayList<>(chunks.subList(0, MAX_CHUNKS_PER_FILE));
        }
        List<VectorStore.VectorRecord> records = new ArrayList<>(chunks.size());
        List<ChunkRef> refs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            progress.accept(new IndexProgress(fileName, IndexStage.EMBEDDING, i + 1, chunks.size()));
            String id = UUID.randomUUID().toString();
            float[] vector = engine.embed(chunk.content());
            records.add(new VectorStore.VectorRecord(id, vector, Map.of(
                    "fileName", fileName,
                    "chunkIndex", chunk.index(),
                    "content", chunk.content(),
                    "category", docCategory)));
            refs.add(new ChunkRef(id, fileName, chunk.index(), chunk.content(), chunk.estimatedTokens()));
        }
        vectorStore.add(records);
        progress.accept(new IndexProgress(fileName, IndexStage.DONE, chunks.size(), chunks.size()));
        return refs;
    }

    /**
     * OCR 产物直接入库（OCR 设计 2026-09-06）：跳过文件解析，以给定文件名+文本走分块/向量化。
     * 供企业服务端图片 OCR 与后续桌面端共用；护栏沿用单文件 50 片上限。
     */
    public List<ChunkRef> addOcrDocument(String displayName, String text, String category)
            throws IOException {
        Objects.requireNonNull(displayName, "displayName");
        EmbeddingEngine engine = requireEngine();
        String docCategory = category == null ? "" : category;
        List<TextChunk> chunks = chunker.chunk(text == null ? "" : text);
        if (chunks.size() > MAX_CHUNKS_PER_FILE) {
            log.info("OCR 文档「{}」切片 {} 片超上限 {}，仅索引前 {} 片", displayName,
                    chunks.size(), MAX_CHUNKS_PER_FILE, MAX_CHUNKS_PER_FILE);
            chunks = new ArrayList<>(chunks.subList(0, MAX_CHUNKS_PER_FILE));
        }
        List<VectorStore.VectorRecord> records = new ArrayList<>(chunks.size());
        List<ChunkRef> refs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String id = UUID.randomUUID().toString();
            float[] vector = engine.embed(chunk.content());
            records.add(new VectorStore.VectorRecord(id, vector, Map.of(
                    "fileName", displayName,
                    "chunkIndex", chunk.index(),
                    "content", chunk.content(),
                    "category", docCategory)));
            refs.add(new ChunkRef(id, displayName, chunk.index(), chunk.content(),
                    chunk.estimatedTokens()));
        }
        vectorStore.add(records);
        return refs;
    }

    /**
     * 以指定显示名入库文件（面板上传用，避免临时文件名暴露）：解析→分块→向量化，文件名=displayName。
     * category 显式传入（空串=未分类）；护栏沿用 10MB/50 片。
     */
    public List<ChunkRef> addParsedDocumentAs(String displayName, Path file, String category)
            throws IOException {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(file, "file");
        String docCategory = category == null ? "" : category;
        long fileBytes = Files.size(file);
        if (fileBytes > MAX_FILE_BYTES) {
            throw new IOException("文件超过单文件解析上限 10 MB: " + displayName);
        }
        EmbeddingEngine engine = requireEngine();
        DocumentParser parser = parsers.stream().filter(p -> p.supports(file.getFileName().toString()))
                .findFirst().orElseThrow(() -> new IOException(unsupportedTypeMessage(displayName)));
        String text = parser.parse(file);
        if (text == null || text.isBlank()) {
            throw new IOException("文档无文本内容（可能是扫描版 PDF 或图片）: " + displayName);
        }
        List<TextChunk> chunks = chunker.chunk(text);
        if (chunks.size() > MAX_CHUNKS_PER_FILE) {
            chunks = new ArrayList<>(chunks.subList(0, MAX_CHUNKS_PER_FILE));
        }
        List<VectorStore.VectorRecord> records = new ArrayList<>(chunks.size());
        List<ChunkRef> refs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String id = UUID.randomUUID().toString();
            float[] vector = engine.embed(chunk.content());
            records.add(new VectorStore.VectorRecord(id, vector, Map.of(
                    "fileName", displayName,
                    "chunkIndex", chunk.index(),
                    "content", chunk.content(),
                    "category", docCategory)));
            refs.add(new ChunkRef(id, displayName, chunk.index(), chunk.content(),
                    chunk.estimatedTokens()));
        }
        vectorStore.add(records);
        return refs;
    }

    /** 已挂载文档清单（知识库管理面板）：按文件名聚合切片数、规模与分类 */
    public List<DocumentInfo> listDocuments() {
        return vectorStore.fileRows().stream()
                .map(row -> new DocumentInfo(row.fileName(), row.category(), row.chunks(), row.chars()))
                .toList();
    }

    /** 移除文档的全部切片（知识库管理面板"删除"）；返回移除的切片数 */
    public int removeDocument(String fileName) {
        Objects.requireNonNull(fileName, "fileName");
        return vectorStore.removeByMetadata("fileName", fileName);
    }

    /**
     * 修改文档分类（知识库管理面板"改分类"，按 file_name 批量更新该文件全部切片）。
     *
     * @param category 新分类；null/空串 = 归入"未分类"
     * @return 实际更新切片数
     */
    public int updateCategory(String fileName, String category) {
        Objects.requireNonNull(fileName, "fileName");
        return vectorStore.updateCategory(fileName, category);
    }

    /** 移除指定分类下全部文件及其切片（面板"删除整类"）；返回移除切片数 */
    public int removeByCategory(String category) {
        return vectorStore.removeByCategory(category);
    }

    /** 全部已用分类（去重升序，知识库面板分类下拉用；不含"未分类"） */
    public List<String> listCategories() {
        return vectorStore.categories();
    }

    /** 查看文档全文（按切片顺序拼接，知识库管理面板"查看"） */
    public String viewDocument(String fileName) {
        Objects.requireNonNull(fileName, "fileName");
        return vectorStore.chunksOf(fileName).stream()
                .map(record -> stringValue(record.metadata().get("content")))
                .collect(Collectors.joining("\n\n"));
    }

    /** 语义检索（需求 4.4：Agent 推理时自动检索相关片段） */
    public List<SearchHit> search(String query, int topK) {
        return search(query, topK, null);
    }

    /**
     * 语义检索（可选按分类过滤，知识库分类：knowledge_search 的 category 参数）。
     *
     * @param category 精确分类；null 或空串 = 全库检索
     */
    public List<SearchHit> search(String query, int topK, String category) {
        EmbeddingEngine engine = requireEngine();
        float[] vector = engine.embed(query);
        return vectorStore.search(vector, topK, category).stream()
                .map(hit -> new SearchHit(
                        stringValue(hit.metadata().get("fileName")),
                        intValue(hit.metadata().get("chunkIndex")),
                        stringValue(hit.metadata().get("content")),
                        hit.score()))
                .toList();
    }

    /** 向量存储描述（UI 展示用） */
    public String storageDescription() {
        return vectorStore.description();
    }

    /** Embedding 引擎描述（UI 展示用；引擎未就绪时含原因） */
    public String embeddingDescription() {
        return embeddingEngine == null ? "未装配" : embeddingEngine.description();
    }

    /** Embedding 引擎是否就绪（启动诊断用：模型已下载/远程可用） */
    public boolean embeddingReady() {
        return embeddingEngine != null && embeddingEngine.isAvailable();
    }

    private EmbeddingEngine requireEngine() {
        if (embeddingEngine == null || !embeddingEngine.isAvailable()) {
            throw new IllegalStateException("Embedding 引擎未就绪：模型缺失或未下载。"
                    + "请检查网络后重试（首次启动会自动下载 " + "sentence-transformers/all-MiniLM-L6-v2" + "），"
                    + "或配置远程 Embedding 服务");
        }
        return embeddingEngine;
    }

    /** 不支持类型提示：图片（无文本层）单独说明，其余给通用提示 */
    private static String unsupportedTypeMessage(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return "图片文件暂不支持入库（无文本层，OCR 规划中）: " + fileName;
        }
        return "不支持的文件类型: " + fileName;
    }

    /**
     * 解析文档分类：显式传入（含空串 = 未分类）按字面采纳；否则自动归类
     * （设计 #2：文件所在目录的 basename；目录在根或无父目录时归入"未分类"）。
     */
    private static String resolveCategory(Path file, String explicit) {
        if (explicit != null) {
            return explicit;
        }
        Path parent = file.toAbsolutePath().getParent();
        return (parent == null || parent.getFileName() == null)
                ? "" : parent.getFileName().toString();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** 切片引用（对应 VectorChunk 表运行期镜像） */
    public record ChunkRef(String id, String fileName, int chunkIndex, String content, int tokenCount) {
    }

    /** 检索命中 */
    public record SearchHit(String fileName, int chunkIndex, String content, double score) {
    }

    /** 挂载进度（知识库面板进度显示） */
    public record IndexProgress(String fileName, IndexStage stage, int doneChunks, int totalChunks) {

        /** 百分比（分块前阶段返回 -1，界面显示阶段文案） */
        public double ratio() {
            return totalChunks == 0 ? -1 : (double) doneChunks / totalChunks;
        }
    }

    /** 挂载阶段 */
    public enum IndexStage {
        PARSING("解析中…"),
        CHUNKING("分块中…"),
        EMBEDDING("向量化中"),
        DONE("完成");

        private final String label;

        IndexStage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 已挂载文档信息（知识库管理面板列表行；category 空串 = 未分类） */
    public record DocumentInfo(String fileName, String category, int chunks, int chars) {
    }
}
