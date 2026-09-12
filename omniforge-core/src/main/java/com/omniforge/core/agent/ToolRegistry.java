package com.omniforge.core.agent;

import com.omniforge.common.spi.Tool;

import java.util.Collection;
import java.util.Optional;

/** 工具注册表：按名称查找、枚举全部工具（会话层据此组装 {@link AgentRunRequest#tools()}）。 */
public interface ToolRegistry {

    /** 按名称查找工具 */
    Optional<Tool> find(String name);

    /** 全部已注册工具 */
    Collection<Tool> all();

    /**
     * 动态注册工具（MCP 服务器工具、插件热加载等运行时注入）。
     * 同名冲突时保留先注册者并返回 false（内置工具优先）。
     *
     * @return true 表示注册成功，false 表示因同名冲突被忽略
     */
    boolean register(Tool tool);

    /** 按名称移除工具（MCP 服务器下线/移除时清理）。
     *
     * @return true 表示确实移除，false 表示不存在 */
    boolean unregister(String name);
}
