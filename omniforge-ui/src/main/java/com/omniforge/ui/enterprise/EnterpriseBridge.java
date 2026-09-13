package com.omniforge.ui.enterprise;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 企业版 UI 桥接 SPI（C-tier 批次 4-2）：ui 不依赖 app 模块，
 * 由 app 装配层实现本接口（包 EnterpriseSessionManager），
 * OmniForgeApplication 经 Spring 容器按类型拾取；未注入时走单机模式。
 */
public interface EnterpriseBridge {

    /** 会话条目（ui 侧中性 DTO） */
    record SessionItem(String id, String name, LocalDateTime createdAt) {
    }

    /** 会话消息（ui 侧中性 DTO） */
    record ChatMessage(String role, String content, LocalDateTime createdAt) {
    }

    /** 对话结果：text=回复文本，sessionId=实际会话（新建时返回服务端生成的 id） */
    record ChatOutcome(String text, String sessionId) {
    }

    /** 登录态失效（服务端 401）：UI 捕获后触发重新登录流程（企业版 P2） */
    final class AuthExpiredException extends RuntimeException {

        public AuthExpiredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 初始服务端地址（登录框预填） */
    String initialServerUrl();

    /** 登录；失败抛异常（消息展示于登录框）；返回服务端角色（企业版 P2 角色感知） */
    String login(String serverUrl, String username, String password) throws Exception;

    /** 保存服务端地址（enterprise.yml）并重设连接 */
    void saveServerUrl(String serverUrl);

    /** 会话缓存（时间倒序） */
    List<SessionItem> sessions();

    /** 重命名会话 */
    void renameSession(String sessionId, String name) throws Exception;

    /** 删除会话 */
    void deleteSession(String sessionId) throws Exception;

    /** 对话（sessionId 空 = 新会话，服务端自动创建） */
    ChatOutcome chat(String sessionId, String prompt) throws Exception;

    /** 会话消息（恢复会话） */
    List<ChatMessage> messages(String sessionId) throws Exception;

    /** D8：协作阶段消息落库（role 固定 collab；viewer 403） */
    void appendSessionMessage(String sessionId, String content) throws Exception;

    /** 当前登录角色（P2-2：admin 显示「🏛 管理」入口；未登录 = ""） */
    String role();

    /** 退出登录（清内存 token/角色/会话缓存；下次操作需重新登录） */
    void logout();

    /** 管理访问（P2-2：服务端强校验 role=admin，非 admin 调用 403 → IllegalStateException） */
    EnterpriseAdminAccess admin();

    // ---- 企业版多模型协作（方案 B 2026-09-05） ----

    record ModelOption(String alias, String providerName) {
    }

    /** 协作运行参数：mode = debate/discussion/brainstorm；judgeAlias 空 = 无裁判 */
    record DebateRun(String topic, List<String> aliases, String judgeAlias, String mode,
                     int maxRounds, Double maxCostUsd) {
    }

    record DebateOutcome(String text, String mode, String judgeAlias, List<String> judgeVerdicts,
                         String winnerAlias, String stopReason) {
    }

    /** 服务端可用模型清单（模式选择勾选用） */
    List<ModelOption> models() throws Exception;

    /** 服务端执行多模型协作（阻塞至完成事件，内部 300s 熔断） */
    DebateOutcome debate(DebateRun run) throws Exception;

    // ---- 多模型协作闭环（A1-A4）：档案 + 讨论→结论→执行→验收 四阶段 ----

    record CollabRunView(String id, String sessionId, String mode, String topic,
                         List<String> aliases,
                         String judgeAlias, String status, int maxRounds,
                         String conclusion, String executionTask, String executorAlias,
                         String executionOutput, String reviewerAlias, String reviewVerdict,
                         String reviewReason, String createdBy, String createdAt, String updatedAt) {
    }

    record CollabList(List<CollabRunView> items, long total) {
    }

    CollabList collabRuns(int page, int size) throws Exception;

    /** 按会话过滤的协作档案（D5 文档产出：导出该会话内发起的协作） */
    CollabList collabRuns(String sessionId, int page, int size) throws Exception;

    CollabRunView collabDetail(String runId) throws Exception;

    String collabTranscript(String runId) throws Exception;

    /** D7 Q6-B：全轮次结构化讨论记录 JSON（旧 run 返回 "{}"） */
    String collabTranscriptData(String runId) throws Exception;

    CollabRunView collabCreate(String topic, List<String> aliases, String judgeAlias,
                               String mode, Integer maxRounds, Double maxCostUsd,
                               String systemText, String sessionId) throws Exception;

    CollabRunView collabConclude(String runId) throws Exception;

    CollabRunView collabExecute(String runId, String task, String executorAlias) throws Exception;

    CollabRunView collabReview(String runId, String reviewerAlias) throws Exception;

    CollabRunView collabRetry(String runId, String task, String executorAlias) throws Exception;

    void collabDelete(String runId) throws Exception;

    // ---- 企业知识库（服务端共享库；member/admin 管理、viewer 只读由服务端强校验） ----

    record KbDocument(String fileName, String category, int chunks, int chars) {
    }

    record KbSearchHit(String fileName, String content, double score) {
    }

    List<KbDocument> kbDocuments() throws Exception;

    List<String> kbCategories() throws Exception;

    List<KbSearchHit> kbSearch(String query, int topK, String category) throws Exception;

    /** 文档全文预览文本（按切片拼接；服务端截断超大文本） */
    String kbDocumentContent(String fileName) throws Exception;

    /** 查看原件：下载原始文件字节（历史数据无原件时服务端 404 中文提示） */
    byte[] kbOriginalFile(String fileName) throws Exception;

    /** 上传文档（返回入库切片数） */
    int kbUpload(String fileName, byte[] content, String category) throws Exception;

    void kbDeleteDocument(String fileName) throws Exception;

    void kbUpdateCategory(String fileName, String category) throws Exception;

    /** 补 OCR：对留存了原件的图片/PDF 重新识别并替换文档内容 */
    void kbReOcr(String fileName) throws Exception;

    void kbDeleteCategory(String category) throws Exception;
}
