package com.omniforge.core.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具参数 JSON Schema 归一化（P1-4 MCP 服务端共用）。
 *
 * <p>模型端（DeepSeek/OpenAI/MCP 客户端）要求函数参数必须是
 * {@code {"type":"object",...}} 的完整对象 schema：空参 {@code {}} 或裸
 * {@code properties} 会被 400 拒绝。本类把空参/缺壳补成 object 壳；已是完整
 * object schema 的原样透传。{@link OmniForgeToolCallback}（Agent 工具调用）与
 * MCP 服务端（外部客户端调用）共用同一实现，避免两份逻辑漂移。</p>
 */
public final class ToolSchema {

    private ToolSchema() {
    }

    /**
     * 归一化为完整 JSON Schema。
     *
     * @param raw 工具原 schema（可能为空、仅 properties、或已是完整 object）
     * @return 保证含 {@code type=object} 的 schema
     */
    public static Map<String, Object> objectSchema(Map<String, Object> raw) {
        if (raw != null && "object".equals(raw.get("type"))) {
            return raw; // 已是完整 JSON Schema，原样透传
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("type", "object");
        wrapped.put("properties", raw == null || raw.isEmpty() ? Map.of() : raw);
        wrapped.put("additionalProperties", Boolean.FALSE);
        return wrapped;
    }
}
