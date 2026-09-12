# OmniForge 插件打包指南（P1-3，2026-09-03）

> 第三方开发者如何为 OmniForge 编写并分发插件。三种格式全纳管：**jar 插件**、
> **MCP 服务器**、**Agent Skills**。分发载体为「插件包」：一个目录或 .zip，
> 内含 `package.manifest.json` + 载荷，拷入市场目录（Win `%APPDATA%\OmniForge\market`、
> Linux `~/.omniforge/market`）即出现在「🧩 插件」市场的可安装列表。

## 1. 插件包结构

```text
<包名>/                       # 目录名随意，市场按 manifest 的 id 识别
├── package.manifest.json     # 必需：包描述（见 §2）
└── payload…                  # 按格式（jar / mcp-server.json / skill 目录）
```

分发：整个目录拷入市场目录；或压缩为 `.zip` 后经市场面板「导入插件包」。
**许可合规**：manifest.license 命中 GPL/AGPL 时安装会被拒绝（贯穿项目的开源合规底线）。

## 2. package.manifest.json

```jsonc
{
  "format": "jar",            // "jar" | "mcp" | "skill"
  "id": "hello",              // 唯一稳定 id（jar=META-INF manifest 或 SPI 名对齐）
  "name": "Hello 示例",        // 展示名
  "version": "1.2.0",          // 升级判断依据（市场按此比较「可升级」）
  "description": "……",
  "author": "……",
  "license": "Apache-2.0",
  "payload": "payload/hello.jar"  // 包内相对路径（jar 为 .jar 文件；mcp 为 mcp-server.json；skill 为含 SKILL.md 的目录）
}
```

## 3. 格式一：jar 插件（代码工具，OmniForgePlugin SPI）

- **只依赖 `omniforge-common`（Apache 2.0）**：实现 `com.omniforge.common.spi.OmniForgePlugin`
  （`getName()` / `getTools()`），工具为 `com.omniforge.common.spi.Tool`（含 ToolSpec 名称/描述/
  JSON Schema 参数）。零 Spring 依赖，无参构造，ServiceLoader 实例化。
- 在 jar 的 `META-INF/services/com.omniforge.common.spi.OmniForgePlugin` 声明实现类（可多个）。
- **可选增强**：jar 内放 `META-INF/omniforge-plugin.json`（同 §2 结构，无需 payload 字段），
  提供版本/作者等元数据（无则降级为 SPI 名）。
- 放置：拷入**插件目录**（`%APPDATA%\OmniForge\plugins`）即自动加载/卸载（WatchService 热生效，
  同名工具先注册者胜）。作为市场包分发时 payload 指向该 jar，安装时市场自动复制到插件目录并保留
  上一版供回滚。

最小实现骨架：

```java
public class HelloPlugin implements OmniForgePlugin {
    @Override public String getName() { return "hello"; }
    @Override public List<Tool> getTools() {
        return List.of(new HelloTool());          // Tool 提供 ToolSpec + execute(ToolRequest)
    }
}
```

- **参数 schema 必须是完整 JSON Schema**：含顶层 `"type": "object"`。**空参工具也要给**
  `Map.of("type", "object", "properties", Map.of())`——传空 `{}` 或仅 `properties` 会被
  部分模型端（DeepSeek/OpenAI）以 400 拒绝（应用适配层已兜底补壳，但按规范填写最稳）。

（一个可编译、含服务声明的完整示例可参考测试源码 `omniforge-core/src/test/.../plugin/testplugin/`
打包；Maven 用 maven-jar-plugin 默认打包即可。）

## 4. 格式二：MCP 服务器（外部工具）

- payload 文件 `mcp-server.json`，字段与 `mcp.yml` 服务器条目同构：

```jsonc
{
  "transport": "stdio",                     // "stdio"（本地进程）| "http"（streamable HTTP）
  "command": "npx",                          // stdio 必填
  "args": ["-y", "@modelcontextprotocol/server-filesystem", "C:/data"],
  "url": ""                                  // http 必填，如 http://localhost:9000/mcp
}
```

- 安装即按 manifest.id 命名并**启用**（工具以 `mcp_<id>_<tool>` 前缀接入 Agent），可在市场或
  配置中心「MCP 服务器」启停。安全：MCP 工具由外部进程执行，不受本地沙箱约束——仅分发可信服务器。

## 5. 格式三：Agent Skills（指令包）

- payload 为**目录**，含 `SKILL.md`；可选 `reference/` 资源。

```text
skill/
└── SKILL.md
```

`SKILL.md` 结构：

```markdown
---
name: 代码评审
description: 面对代码评审类请求时采用的结构与侧重点
---
当你需要评审代码时：
1. 先指出会导致错误/崩溃的问题，再谈风格与可维护性
2. 每条意见给出「文件:行 → 理由 → 建议」
3. ……
```

- 安装即默认**启用**；启用技能每次 Agent 单模型调用并入系统提示（提示模型「仅当任务与之相关时
  遵循」，位置在调用方系统提示之后、基线规则之前）。指令总量上限 8000 字（整体截断并告警）——
  **正文请精简**，避免被截尾。禁用/卸载经市场「已安装」页技能行操作。
- 影响面：仅本地单模型 Agent 路径；辩论/裁判、企业版服务端 Agent 不受技能影响。

## 6. 本地自测

1. 把包目录放入 `market/`（或「导入插件包」选 .zip）；
2. 打开「🧩 插件」→ 市场出现该包 → 安装（jar/MCP 会弹确认）；
3. 「已安装」查看：jar 显示其插件与工具、技能可启停、MCP 可启停；
4. Agent 模式下输入触发技能场景，观察系统提示注入是否生效（日志可见消息构成 N=system/history…）。

## 7. 版本与回滚

- 市场按 `version` 判断「可升级」；安装/升级会保留**上一活跃版**于
  `market/archive/<id>/previous.jar`，「回滚」一键恢复。
- 覆盖安装同一 id 的不同格式视为不同安装，市场按 id 唯一（同一 id 换格式建议改 id）。

## 8. 已知边界（不做）

- OpenAI Agent Plugins（OpenAPI 远程适配）明确不做：与 MCP 重叠且工程量大（见
  docs/PLUGIN_MARKET_DESIGN.md §1#11）。
- 服务端插件元数据 API（企业目录/审批）为预留设计，`PluginCatalogEntry` 与本地
  `package.manifest.json` 同构，PG 环境就绪后按 C 级批次激活。
