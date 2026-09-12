package com.omniforge.core.agent;

import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认工具注册表：聚合所有 {@link ToolProvider}（ServiceLoader 机制）。
 *
 * <p>omniforge-tools / omniforge-knowledge 等模块通过
 * META-INF/services/com.omniforge.common.spi.ToolProvider 声明提供者即被自动发现；
 * 插件热加载（plugins/*.jar）后续通过 {@link #register(Tool)} 动态注入。</p>
 */
public class DefaultToolRegistry implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolRegistry.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /** 通过 ServiceLoader 发现全部 ToolProvider */
    public DefaultToolRegistry() {
        this(ServiceLoader.load(ToolProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList());
    }

    /** 从显式提供者列表构建（测试/手动装配用） */
    public DefaultToolRegistry(List<ToolProvider> providers) {
        for (ToolProvider provider : providers) {
            for (Tool tool : provider.tools()) {
                register(tool);
            }
        }
    }

    /**
     * 合并构建：显式提供者（Spring 装配）优先注册，ServiceLoader 提供者兜底追加。
     * 重名冲突时先注册者胜——保证 Spring 管理的共享实例（如 knowledge_search 的
     * 共享向量存储）优先于 ServiceLoader 自建实例。
     */
    public static DefaultToolRegistry merging(List<ToolProvider> explicitProviders) {
        DefaultToolRegistry registry = new DefaultToolRegistry(explicitProviders);
        ServiceLoader.load(ToolProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .forEach(provider -> {
                    for (Tool tool : provider.tools()) {
                        registry.register(tool);
                    }
                });
        return registry;
    }

    /**
     * 注册单个工具；同名冲突时保留先注册者并告警（插件热加载入口）。
     *
     * @return true 表示注册成功，false 表示因同名冲突被忽略
     */
    @Override
    public boolean register(Tool tool) {
        String name = tool.spec().name();
        Tool existing = tools.putIfAbsent(name, tool);
        if (existing != null) {
            log.warn("工具重名冲突，忽略后注册者：name={}（已存在 {}）", name, existing.getClass().getName());
            return false;
        }
        log.info("已注册工具：{}（{}）", name, tool.getClass().getName());
        return true;
    }

    /** 按名称移除工具（MCP 服务器下线/移除时清理）。
     *
     * @return true 表示确实移除，false 表示不存在 */
    @Override
    public boolean unregister(String name) {
        Tool removed = tools.remove(name);
        if (removed != null) {
            log.info("已移除工具：{}（{}）", name, removed.getClass().getName());
            return true;
        }
        return false;
    }

    @Override
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Collection<Tool> all() {
        return List.copyOf(tools.values());
    }
}
