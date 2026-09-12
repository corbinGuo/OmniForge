package com.omniforge.app.enterprise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 企业版会话管理（C-tier 批次 4-2）：Token 与会话缓存均内存态。
 *
 * <ul>
 *   <li>Token 内存存储，重启需重新登录（设计确认 #1）；</li>
 *   <li>会话缓存内存态，登录成功后从服务端拉取（设计确认 #2），
 *       增删改同步服务端并刷新缓存；</li>
 *   <li>服务端地址可热改（登录框保存时重设），token 自动清空。</li>
 * </ul>
 */
public class EnterpriseSessionManager {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseSessionManager.class);

    private volatile EnterpriseApiClient client;
    /** 登录角色（P2-2：admin 入口可见性 / 服务端仍强校验） */
    private volatile String role = "";
    /** 会话缓存（内存，设计确认 #2；按服务端返回的时间倒序） */
    private final CopyOnWriteArrayList<EnterpriseApiClient.SessionSummary> sessions =
            new CopyOnWriteArrayList<>();

    public EnterpriseSessionManager(String serverUrl) {
        this.client = new EnterpriseApiClient(serverUrl);
    }

    /** 测试注入：复用已有客户端 */
    EnterpriseSessionManager(EnterpriseApiClient client) {
        this.client = client;
    }

    /** 已登录（内存 token 存在） */
    public boolean isLoggedIn() {
        return client.isLoggedIn();
    }

    /** 登录角色（未登录/空 = ""） */
    public String role() {
        return role;
    }

    /** 底层客户端（包内：Bridge 管理透传经此读取，避免逐方法重复转发） */
    EnterpriseApiClient api() {
        return client;
    }

    /** 登录并拉取会话列表（设计确认 #2）；返回角色（企业版 P2/P2-2 角色感知） */
    public String login(String username, String password) throws Exception {
        EnterpriseApiClient.LoginResult result = client.login(username, password);
        this.role = result.role() == null ? "" : result.role();
        refreshSessions();
        log.info("企业版登录成功：{}（角色 {}）", username, role);
        return role;
    }

    /** 退出（清 token、角色与缓存） */
    public void logout() {
        client.logout();
        role = "";
        sessions.clear();
    }

    /** 重设服务端地址（清 token；登录框保存地址时调用） */
    public void setServerUrl(String serverUrl) {
        client = new EnterpriseApiClient(serverUrl);
        sessions.clear();
    }

    /** 当前缓存会话（时间倒序） */
    public List<EnterpriseApiClient.SessionSummary> sessions() {
        return List.copyOf(sessions);
    }

    /** 从服务端重新拉取会话列表 */
    public void refreshSessions() throws Exception {
        sessions.clear();
        sessions.addAll(client.listSessions());
    }

    /** 重命名会话（服务端 + 缓存同步） */
    public void renameSession(String sessionId, String name) throws Exception {
        EnterpriseApiClient.SessionSummary renamed = client.renameSession(sessionId, name);
        replaceInCache(renamed);
    }

    /** 删除会话（服务端 + 缓存同步） */
    public void deleteSession(String sessionId) throws Exception {
        client.deleteSession(sessionId);
        sessions.removeIf(session -> session.id().equals(sessionId));
    }

    /**
     * 对话：sessionId 为空时服务端自动新建会话（名称取首条消息前 30 字），
     * 返回结果携带实际 sessionId；新建后刷新会话缓存。
     */
    public EnterpriseApiClient.ChatResult chat(String sessionId, String prompt) throws Exception {
        EnterpriseApiClient.ChatResult result = client.chat(sessionId, prompt);
        if ((sessionId == null || sessionId.isBlank()) && result.sessionId() != null) {
            refreshSessions();
        }
        return result;
    }

    /** 会话消息（恢复会话） */
    public List<EnterpriseApiClient.SessionMessage> sessionMessages(String sessionId)
            throws Exception {
        return client.sessionMessages(sessionId);
    }

    private void replaceInCache(EnterpriseApiClient.SessionSummary renamed) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id().equals(renamed.id())) {
                sessions.set(i, renamed);
                return;
            }
        }
    }
}
