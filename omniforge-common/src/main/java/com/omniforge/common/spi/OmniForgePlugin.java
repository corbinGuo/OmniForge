package com.omniforge.common.spi;

import java.util.List;

/**
 * 插件 SPI（C-tier 批次 3：插件热加载，需求 4.5 插件生态）。
 *
 * <p>插件以 jar 形式放置于配置目录 {@code plugins/} 下，随应用启动扫描加载，
 * 目录变化（增/改/删）自动热加载/卸载。插件 jar 需在
 * {@code META-INF/services/com.omniforge.common.spi.OmniForgePlugin} 中声明实现类
 * （一个 jar 可声明多个插件）。</p>
 *
 * <p>插件作者只需依赖 omniforge-common（Apache 2.0），通过 {@link Tool} SPI
 * 提供工具；由应用侧的 ToolRegistry 统一注册、Agent 引擎统一调用。</p>
 *
 * <p>实现要求：</p>
 * <ul>
 *   <li>{@link #getName()} 应返回稳定唯一标识（同目录同名插件重复加载时先注册者胜）；</li>
 *   <li>{@link #getTools()} 可返回空列表（无工具的观察型插件），不得返回 null；</li>
 *   <li>插件类需有无参构造（经 ServiceLoader 反射实例化）。</li>
 * </ul>
 */
public interface OmniForgePlugin {

    /** 插件唯一名称（用于日志与冲突识别） */
    String getName();

    /** 插件提供的工具列表（可空列表，不可 null） */
    List<Tool> getTools();
}
