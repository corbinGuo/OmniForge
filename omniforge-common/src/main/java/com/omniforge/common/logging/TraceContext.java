package com.omniforge.common.logging;

import org.slf4j.MDC;

/**
 * 日志追踪上下文：向 MDC 写入会话/模型/工作区等维度，
 * 供 Log4j2 输出统一格式（如 %X{sessionId}）。
 *
 * <p>MDC 是线程本地存储，虚拟线程场景下与任务绑定传播；
 * 任务结束必须调用 {@link #clear()} 避免串扰。</p>
 */
public final class TraceContext {

    public static final String KEY_SESSION_ID = "sessionId";
    public static final String KEY_MODEL = "model";
    public static final String KEY_WORKSPACE = "workspace";

    private TraceContext() {
    }

    /** 写入自定义维度 */
    public static void put(String key, String value) {
        MDC.put(key, value);
    }

    /** 标记当前会话 */
    public static void session(String sessionId) {
        MDC.put(KEY_SESSION_ID, sessionId);
    }

    /** 标记当前模型 */
    public static void model(String modelName) {
        MDC.put(KEY_MODEL, modelName);
    }

    /** 清除全部维度（任务结束必须调用） */
    public static void clear() {
        MDC.clear();
    }
}
