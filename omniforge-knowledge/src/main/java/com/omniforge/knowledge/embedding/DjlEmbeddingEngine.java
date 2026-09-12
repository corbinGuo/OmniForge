package com.omniforge.knowledge.embedding;

import ai.djl.MalformedModelException;
import ai.djl.Model;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import com.omniforge.knowledge.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DJL + ONNX 向量化引擎（需求 4.4：sentence-transformers/all-MiniLM-L6-v2，384 维）。
 *
 * <p>模型获取（决议 #8）：安装包不含权重；{@link #isAvailable()} 检测模型目录，
 * 缺失时若开启 auto-download 则从 HuggingFace（或镜像 hf-mirror.com）下载：
 * ONNX 权重（仓库 onnx/ 子目录）与 tokenizer 文件（tokenizer.json/vocab.txt 等）。
 * 下载失败返回 false（上层可提示重试/跳过/改用远程服务）。</p>
 */
public class DjlEmbeddingEngine implements EmbeddingEngine {

    private static final Logger log = LoggerFactory.getLogger(DjlEmbeddingEngine.class);

    /** 本地权重文件名（下载自仓库 onnx/ 子目录，仓库根目录无 model.onnx） */
    private static final String MODEL_FILE = "model.onnx";
    /** 仓库内 ONNX 权重相对路径 */
    private static final String REMOTE_MODEL_PATH = "onnx/model.onnx";
    /** 必需的分词器文件（下载自仓库根目录） */
    private static final List<String> TOKENIZER_FILES = List.of(
            "tokenizer.json", "tokenizer_config.json", "vocab.txt", "special_tokens_map.json");

    private final KnowledgeProperties properties;
    private final AtomicBoolean availabilityChecked = new AtomicBoolean(false);
    private volatile boolean available = false;
    private volatile ZooModel<String, float[]> zooModel;
    private volatile Predictor<String, float[]> predictor;

    public DjlEmbeddingEngine(KnowledgeProperties properties) {
        this.properties = properties;
    }

    /** 无参构造（ServiceLoader 使用）：默认配置 */
    public DjlEmbeddingEngine() {
        this(new KnowledgeProperties());
    }

    @Override
    public synchronized boolean isAvailable() {
        if (availabilityChecked.get()) {
            return available;
        }
        availabilityChecked.set(true);
        try {
            if (!hasModelFiles()) {
                if (properties.isAutoDownloadEnabled()) {
                    downloadModel();
                } else {
                    log.warn("Embedding 模型缺失且自动下载已禁用：{}", properties.getModelDir());
                }
            }
            if (!hasModelFiles()) {
                return false;
            }
            // 预热：加载模型与预测器，失败视为不可用
            loadModel();
            available = true;
        } catch (Exception e) {
            log.warn("Embedding 引擎不可用：{}", e.getMessage());
        }
        return available;
    }

    @Override
    public float[] embed(String text) {
        if (!isAvailable()) {
            throw new IllegalStateException("Embedding 引擎不可用：模型未就绪，请检查网络或改用远程 Embedding 服务");
        }
        try {
            return predictor.predict(text);
        } catch (Exception e) {
            throw new IllegalStateException("向量化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String description() {
        String state = availabilityChecked.get() ? (available ? "就绪" : "未就绪（下载失败，见日志）") : "未初始化";
        return "DJL/ONNX " + properties.getModelName() + "（" + dimensions() + " 维，" + state + "）";
    }

    @Override
    public int dimensions() {
        return 384; // all-MiniLM-L6-v2 输出维度
    }

    private boolean hasModelFiles() {
        Path dir = properties.getModelDir();
        return Files.exists(dir.resolve(MODEL_FILE)) && Files.exists(dir.resolve("tokenizer.json"));
    }

    /** 下载模型与分词器文件（包内可见，测试注入本地 HttpServer 验证下载路径） */
    void downloadModel() throws IOException, InterruptedException {
        Path dir = properties.getModelDir();
        Files.createDirectories(dir);
        log.warn("首次使用：正在下载 Embedding 模型 {}（约 90MB）到 {} ...",
                properties.getModelName(), dir.toAbsolutePath());
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        String repo = properties.getModelName() + "/resolve/main/";
        // ONNX 权重在仓库 onnx/ 子目录（仓库根目录无 model.onnx，直接下载会 404）
        download(client, dir.resolve(MODEL_FILE), repo + REMOTE_MODEL_PATH);
        for (String tokenizerFile : TOKENIZER_FILES) {
            download(client, dir.resolve(tokenizerFile), repo + tokenizerFile);
        }
        log.info("Embedding 模型下载完成：{}", dir.toAbsolutePath());
    }

    private void download(HttpClient client, Path target, String hfPath) throws IOException, InterruptedException {
        String url = properties.getHuggingFaceBaseUrl() + hfPath;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(properties.getDownloadTimeoutSeconds()))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("下载失败 HTTP " + response.statusCode() + ": " + url);
        }
        Path temp = Files.createTempFile(target.getFileName().toString(), ".part");
        try (InputStream in = response.body()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private synchronized void loadModel() throws IOException, MalformedModelException, ModelNotFoundException {
        if (predictor != null) {
            return;
        }
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(properties.getModelDir(), Map.of());
        // 自研翻译器：显式命名输入张量 + mask 加权 mean pooling
        // （DJL 内置 TextEmbeddingTranslator 不给张量命名，与具名 ONNX 输入不匹配）
        MiniLmEmbeddingTranslator translator = new MiniLmEmbeddingTranslator(tokenizer);
        Criteria<String, float[]> criteria = (Criteria) Criteria.builder()
                .setTypes(String.class, float[].class)
                // 模型目录加载（optModelPath 指向目录；指向 .onnx 文件会找不到模型）
                .optModelPath(properties.getModelDir())
                .optEngine("OnnxRuntime")
                // 必须经 optTranslator 注入：本地模型加载器（BaseModelLoader.loadModel）
                // 会用 DefaultTranslatorFactory 匹配 String→float[]，未注册则抛
                // ModelNotFoundException "No matching default translator found"
                .optTranslator(translator)
                .build();
        this.zooModel = criteria.loadModel();
        this.predictor = zooModel.newPredictor(translator);
    }

    /** 释放模型资源（关闭时调用） */
    public synchronized void close() {
        if (predictor != null) {
            predictor.close();
            predictor = null;
        }
        if (zooModel != null) {
            zooModel.close();
            zooModel = null;
        }
        availabilityChecked.set(false);
        available = false;
    }
}
