package com.omniforge.ui.plugin;

import java.nio.file.Path;
import java.util.List;

/**
 * 插件市场 UI 桥接 SPI（P1-3）：ui 不依赖 app 模块，
 * 由 app 装配层的 {@code MarketManager} 实现本接口，OmniForgeApplication
 * 经 Spring 容器按类型拾取；未注入时主界面不显示「🧩 插件」入口。
 *
 * <p>三格式全纳管：jar（OmniForgePlugin）/ mcp / skill；本接口只暴露
 * 中立 DTO 与动作，避免 ui 依赖任何市场实现细节。</p>
 */
public interface PluginBridge {

    String FORMAT_JAR = "jar";
    String FORMAT_MCP = "mcp";
    String FORMAT_SKILL = "skill";

    /** 安装状态（机器可读；UI 负责中文本地化） */
    String STATE_NOT_INSTALLED = "NOT_INSTALLED";
    String STATE_INSTALLED = "INSTALLED";
    String STATE_UPGRADABLE = "UPGRADABLE";

    /** 市场「可安装包」条目 */
    record AvailablePlugin(String format, String id, String name, String version,
                           String description, String author, String license, String state) {
    }

    /** 已安装 jar 插件（来自插件目录运行时加载记录） */
    record InstalledJar(String id, String fileName, List<String> pluginNames, List<String> tools) {
    }

    /** 已安装/启用的 MCP 服务器 */
    record InstalledMcp(String name, String transport, boolean enabled) {
    }

    /** 已安装技能 */
    record InstalledSkill(String name, String description, boolean enabled) {
    }

    /** 市场一页快照 */
    record Overview(List<AvailablePlugin> available, List<InstalledJar> jars,
                    List<InstalledMcp> mcpServers, List<InstalledSkill> skills, String marketDir) {
    }

    /** 当前市场快照（可用包 + 三格式已装态） */
    Overview overview();

    /** 安装/升级市场包（按 id 定位 <市场目录>/&lt;id&gt;/package.manifest.json） */
    void install(String id) throws Exception;

    /** 卸载（jar 删 plugins/ 文件、mcp 移出 mcp.yml、skill 删除 skills/ 目录） */
    void uninstall(String id) throws Exception;

    /** 回滚上一活跃版（目前 jar 格式支持，其余抛不支持提示） */
    void rollback(String id) throws Exception;

    /** 启停（jar 不支持；mcp=置 enabled、skill=改启用标记） */
    void setEnabled(String format, String id, boolean enabled) throws Exception;

    /** 导入插件包（zip 或已解包目录）到市场目录 */
    void importPackage(Path archive) throws Exception;
}
