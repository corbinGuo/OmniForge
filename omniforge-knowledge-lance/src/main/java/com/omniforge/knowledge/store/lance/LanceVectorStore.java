package com.omniforge.knowledge.store.lance;

import com.omniforge.knowledge.store.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * LanceDB 持久化向量存储（需求 4.4：嵌入式、零配置，数据存 ~/.omniforge/vectors/）。
 *
 * <p>⚠️ 待验证：本机镜像源缺失 com.lancedb:lancedb-core，本类尚未经编译验证；
 * 网络可达 Maven Central 后以 `mvn -P lance verify` 构建，并按 lancedb-core 0.19.1
 * 实际 API 修正 TODO 标记处（连接、建表、ANN 检索）。</p>
 */
public class LanceVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(LanceVectorStore.class);

    private final String dbUri;
    private final String tableName;
    private volatile boolean available = false;
    private volatile String availabilityError = "LanceDB 未初始化";

    /**
     * @param dbUri     LanceDB 数据目录（如 ~/.omniforge/vectors）
     * @param tableName 表名（如 omniforge_chunks）
     */
    public LanceVectorStore(String dbUri, String tableName) {
        this.dbUri = dbUri;
        this.tableName = tableName;
        try {
            initialize();
            available = true;
        } catch (Throwable e) {
            availabilityError = e.getMessage();
            log.warn("LanceDB 初始化失败（将回退内存存储）：{}", e.getMessage());
        }
    }

    /** 无参构造（ServiceLoader 使用）：默认数据目录 */
    public LanceVectorStore() {
        this(defaultDbUri(), "omniforge_chunks");
    }

    private void initialize() {
        // TODO(待编译验证): 按 lancedb-core 0.19.1 实际 API 实现
        //   LanceDb.connect(dbUri) -> 建表/打开表：
        //   表结构: id STRING, vector FLOAT[384], fileName STRING, chunkIndex INT, content STRING
        //   若表不存在: createTable(tableName, List<Map<String,Object>> 初始数据为空需带 schema)
        throw new UnsupportedOperationException(
                "LanceVectorStore 待网络可达后按 lancedb-core 0.19.1 API 完成编译验证（见类注释）");
    }

    @Override
    public void add(List<VectorRecord> records) {
        checkAvailable();
        // TODO(待编译验证): 逐条转 Map 后 table.add(...)
        throw new UnsupportedOperationException("待验证");
    }

    @Override
    public List<VectorHit> search(float[] query, int topK) {
        checkAvailable();
        // TODO(待编译验证): table 的 ANN 检索（vector search），映射回 VectorHit
        throw new UnsupportedOperationException("待验证");
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String description() {
        return "LanceDB " + dbUri + (available ? "" : "（不可用：" + availabilityError + "）");
    }

    private void checkAvailable() {
        if (!available) {
            throw new IllegalStateException("LanceDB 存储不可用：" + availabilityError);
        }
    }

    private static String defaultDbUri() {
        String userHome = System.getProperty("user.home", ".");
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            String base = (appData != null && !appData.isBlank()) ? appData : userHome;
            return base + "\\OmniForge\\vectors";
        }
        return userHome + "/.omniforge/vectors";
    }
}
