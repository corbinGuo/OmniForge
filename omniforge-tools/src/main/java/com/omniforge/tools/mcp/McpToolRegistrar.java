package com.omniforge.tools.mcp;

import com.omniforge.core.agent.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP 工具注册桥：把 {@link McpClientManager} 的当前工具同步进 {@link ToolRegistry}
 * （Agent/辩论引擎即与内置工具一样看到 MCP 工具）。
 *
 * <p>同步策略：
 * <ul>
 *   <li>连接成功/失败/服务器端工具变化时经 changeListener 刷新；</li>
 *   <li>新工具逐个注册——同名冲突时内置工具优先（MCP 让位并告警，registry 语义）；</li>
 *   <li>已消失的 MCP 工具（服务器下线/移除/禁用）自动 unregister；</li>
 *   <li>ToolRegistry 未装配时静默跳过（理论不发生：core 装配恒有注册表）。</li>
 * </ul>
 */
public class McpToolRegistrar implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistrar.class);

    private final McpClientManager manager;
    private final ToolRegistry registry; // 可为 null
    private volatile Set<String> registeredNames = Set.of();
    private volatile boolean running;

    public McpToolRegistrar(McpClientManager manager, ToolRegistry registry) {
        this.manager = manager;
        this.registry = registry;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        manager.setChangeListener(this::refresh);
        refresh(manager.tools());
    }

    @Override
    public void stop() {
        running = false;
        if (registry != null) {
            registeredNames.forEach(registry::unregister);
        }
        registeredNames = Set.of();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 同步注册表：注册新工具、移除已消失的 MCP 工具 */
    synchronized void refresh(List<McpToolAdapter> tools) {
        if (!running || registry == null) {
            return;
        }
        Set<String> current = tools.stream().map(tool -> tool.spec().name()).collect(Collectors.toSet());
        registeredNames.stream()
                .filter(name -> !current.contains(name))
                .forEach(registry::unregister);
        for (McpToolAdapter tool : tools) {
            if (!registeredNames.contains(tool.spec().name())) {
                registry.register(tool);
            }
        }
        registeredNames = current;
    }
}
