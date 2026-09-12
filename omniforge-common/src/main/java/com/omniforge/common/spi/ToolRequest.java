package com.omniforge.common.spi;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工具调用请求。
 *
 * @param parameters 工具参数（键值对，由调用方按 {@link ToolSpec#parametersSchema()} 组装）
 * @param sessionId  触发该调用的会话 ID（可为空，例如系统级调用）
 * @param attributes 扩展属性（工作区路径、用户身份等上下文信息）
 */
public record ToolRequest(Map<String, Object> parameters, String sessionId, Map<String, Object> attributes) {

    public ToolRequest {
        parameters = parameters == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(parameters));
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    /** 仅带参数的便捷构造 */
    public static ToolRequest of(Map<String, Object> parameters) {
        return new ToolRequest(parameters, null, Map.of());
    }

    /** 读取单个参数，缺失时返回 null */
    public Object parameter(String name) {
        return parameters.get(Objects.requireNonNull(name, "name"));
    }

    /** 读取扩展属性，缺失时返回 null */
    public Object attribute(String key) {
        return attributes.get(Objects.requireNonNull(key, "key"));
    }
}
