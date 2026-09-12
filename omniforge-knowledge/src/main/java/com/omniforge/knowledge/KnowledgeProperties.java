package com.omniforge.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 知识库配置（前缀 {@code omniforge.knowledge}）。
 *
 * <p>模型下载策略（决议 #8）：安装包不含权重，首启检测模型目录，
 * 缺失时从 HuggingFace 自动下载（可关闭；失败可重试/跳过，或配置远程 Embedding 服务）。</p>
 */
@ConfigurationProperties(prefix = "omniforge.knowledge")
public class KnowledgeProperties {

    /** Embedding 模型名（HuggingFace 仓库名） */
    private String modelName = "sentence-transformers/all-MiniLM-L6-v2";

    /** 模型本地目录（默认 <配置目录>/models/all-MiniLM-L6-v2） */
    private Path modelDir = defaultConfigDir().resolve("models").resolve("all-MiniLM-L6-v2");

    /** HuggingFace 基础地址（可替换为镜像 hf-mirror.com） */
    private String huggingFaceBaseUrl = "https://huggingface.co/";

    /** 首启自动下载模型（决议 #8） */
    private boolean autoDownloadEnabled = true;

    /** 模型下载超时（秒） */
    private long downloadTimeoutSeconds = 600;

    /** 分块目标长度（字符，≈512 token） */
    private int chunkMaxChars = 1024;

    /** 重叠比例（需求 4.4：Overlap=10%） */
    private double overlapRatio = 0.1;

    /** 默认检索条数 */
    private int topKDefault = 5;

    /** 远程 Embedding 服务地址（如 Ollama：http://localhost:11434）；配置后不再使用本地 DJL 模型（决议 #8） */
    private String remoteUrl;

    /** 远程 Embedding 模型名（默认 nomic-embed-text） */
    private String remoteModel = "nomic-embed-text";

    /** 向量库文件（SQLite，默认 <配置目录>/vectors.db；2026-09 决议：LanceDB 归档，见 docs/LANCE_RETIRED.md） */
    private Path vectorDbFile = defaultConfigDir().resolve("vectors.db");

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Path getModelDir() {
        return modelDir;
    }

    public void setModelDir(Path modelDir) {
        this.modelDir = modelDir;
    }

    public String getHuggingFaceBaseUrl() {
        return huggingFaceBaseUrl;
    }

    public void setHuggingFaceBaseUrl(String huggingFaceBaseUrl) {
        this.huggingFaceBaseUrl = huggingFaceBaseUrl;
    }

    public boolean isAutoDownloadEnabled() {
        return autoDownloadEnabled;
    }

    public void setAutoDownloadEnabled(boolean autoDownloadEnabled) {
        this.autoDownloadEnabled = autoDownloadEnabled;
    }

    public long getDownloadTimeoutSeconds() {
        return downloadTimeoutSeconds;
    }

    public void setDownloadTimeoutSeconds(long downloadTimeoutSeconds) {
        this.downloadTimeoutSeconds = downloadTimeoutSeconds;
    }

    public int getChunkMaxChars() {
        return chunkMaxChars;
    }

    public void setChunkMaxChars(int chunkMaxChars) {
        this.chunkMaxChars = chunkMaxChars;
    }

    public double getOverlapRatio() {
        return overlapRatio;
    }

    public void setOverlapRatio(double overlapRatio) {
        this.overlapRatio = overlapRatio;
    }

    public int getTopKDefault() {
        return topKDefault;
    }

    public void setTopKDefault(int topKDefault) {
        this.topKDefault = topKDefault;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public String getRemoteModel() {
        return remoteModel;
    }

    public void setRemoteModel(String remoteModel) {
        this.remoteModel = remoteModel;
    }

    public Path getVectorDbFile() {
        return vectorDbFile;
    }

    public void setVectorDbFile(Path vectorDbFile) {
        this.vectorDbFile = vectorDbFile;
    }

    /** 默认配置目录：Linux ~/.omniforge；Windows %APPDATA%\OmniForge（与其他模块一致） */
    private static Path defaultConfigDir() {
        String userHome = System.getProperty("user.home", ".");
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.isBlank()) ? Paths.get(appData) : Paths.get(userHome);
            return base.resolve("OmniForge");
        }
        return Paths.get(userHome, ".omniforge");
    }
}
