package com.omniforge.common.spi;

import java.util.List;

/**
 * 工具提供者 SPI。插件（plugins/*.jar）通过 ServiceLoader 机制注册工具集
 * （在 META-INF/services/com.omniforge.common.spi.ToolProvider 中声明实现类）。
 *
 * <p>示例：内置工具包与知识库检索工具各自实现本接口，
 * 由装配层（omniforge-app）聚合后注入 Agent 引擎。</p>
 */
public interface ToolProvider {

    /** 提供者名称（如 "builtin" / "knowledge"） */
    String name();

    /** 该提供者贡献的工具列表（线程安全、可重复调用） */
    List<Tool> tools();
}
