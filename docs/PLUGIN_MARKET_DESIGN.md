# 插件市场设计（P1-3，2026-09-03 待用户编号确认）

> 范围（用户 AskUserQuestion 三项拍板）：
> 1. **市场形态** = 两者都要（本地目录市场本批落地，服务端元数据 API 预留接口/结构，PG 就绪后激活）。
> 2. **插件格式** = 原生 OmniForge jar + MCP + Agent Skills **全纳管**。
> 3. **三侧侧重** = 用户侧为主 + 出《插件打包指南》；管理员审批/服务端发布留后。

现有底座（复用，非新建）：`plugins/*.jar` 热加载（`PluginManager` + WatchService + staging 副本 + 回滚）、
MCP Client（`tools.mcp`：mcp.yml + `McpSettingsHolder` 热生效 + 连接重建）、
配置目录统一 `<配置目录>`（Win `%APPDATA%\OmniForge` / Linux `~/.omniforge`）。

---

## 1. 决策点（请按编号回复 确认/修改）

| # | 决策 | 建议（默认） |
|---|------|------|
| 1 | **入口形态** | 主界面导航新增「🧩 插件」按钮，打开独立 `PluginMarketDialog`（两栏：市场/已安装），**不进配置中心侧栏**（配置中心 = 配置持久化模块；插件市场 = 动作/生命周期，分离更清晰）。 |
| 2 | **市场目录布局** | `<配置目录>/market/` 下每目录一个「可安装插件包」`market/<包名>/`，内含 `package.manifest.json` + 载荷（jar / mcp-server.json / skills 目录）。用户在资源管理器**拷入目录即出现在「市场」列表**（打开时扫描 + WatchService 热刷新）。 |
| 3 | **安装动作 = 文件/配置操作（复用既有热加载）** | jar 安装 → 复制到 `plugins/`（现有 WatchService 自动加载，同名覆盖即重载）；卸载 → 删 `plugins/` 下对应 jar（自动卸载）。MCP 安装 → 向 `mcp.yml` 追加服务器项 + `holder.update`（复用重建连接）。Skill 安装 → 解包到 `<配置目录>/skills/<name>/`。**不新增自己的运行时加载器**。 |
| 4 | **版本/升级/回滚（jar）** | 活跃 jar 以稳定名 `plugins/<id>.jar` 存在；安装/升级前把当前活跃版**备份**到 `market/archive/<id>/<version>.jar`（仅存上一版）；升级 = 覆盖活跃 jar（MODIFY 事件自动重载）；回滚 = 把 archive 版拷回覆盖。状态记 `market/state.json`。 |
| 5 | **插件元数据 manifest** | 增强插件 jar：`META-INF/omniforge-plugin.json`（id/name/version/description/author/license/minVersion）。**有则读，无则降级**（id=SPI `getName()`、version=“0”、desc 空）——存量插件零改动。工具清单仍以运行时 `ToolRegistry`/插件 `getTools()` 为准。 |
| 6 | **MCP 作为市场条目** | `package.manifest.json` 的 `format=mcp`，载荷给出 `McpServerConfig`（name/transport/command+args 或 url/enabled），安装=追加进 mcp.yml（默认 enabled=true，可由 MCP 模块再关）。市场本身不做进程级健康探测（复用 MCP 模块既有日志）。 |
| 7 | **Agent Skills 语义（关键）** | Skill = `<skills>/<name>/` 内含 `SKILL.md`（frontmatter: name/description + 正文指令）+ 可选 `reference/` 资源。**启用**的 Skill 指令块在每次 Agent 单模型调用时并入系统提示（放在调用方 systemText 之后、BASE_SYSTEM_PROMPT 之前）。不做自动匹配/工具式调用（模型 tool-calling 不可靠），v1 = 常驻指令 + 用户可勾选；指令总量设上限（默认 8000 字，超出告警截断）。 |
| 8 | **Skills 注入点的层解耦** | `core` 新增 SPI `SystemInstructionContributor`（`String extraSystemText()`）；`ReactAgentLoop` 经 ObjectProvider 注入（可空），`buildMessages()` 拼装时并入。实现放 app 装配层（读 skills 目录 + skills.yml 启用集），core 不依赖具体 Skill 实现。辩论/裁判路径本批不受 Skills 影响。 |
| 9 | **服务端元数据 API（预留）** | 定义 `PluginCatalogEntry` 结构（与 package.manifest.json 同构 + downloadUrl/size/hash）+ `PluginCatalogSource` SPI（local 实现 = 扫 market/ 目录）。远程/enterprise 实现与 `GET /api/plugins/catalog` 端点**本批只留接口不实现**（PG/服务端验收延后，见 §8）。 |
| 10 | **三侧边界** | 本批：用户侧面板 + 打包文档。开发者 = 写 jar/skill/mcp 描述 + `package.manifest.json` 拷入 market 即可。管理员审批/服务端发布 = 归档 GAP，本批不做。 |
| 11 | **范围澄清：OpenAI Agent Plugins** | 需求文原词含「OpenAI Agent Plugins」，其形态是 OpenAPI 远程工具适配（≈HTTP 工具网关，比 MCP 重且与之重叠）。**建议本批不做、保持 3 格式**（jar/MCP/Skills），OpenAPI 远程适配留后续单独评估。 |
| 12 | **命名冲突与卸载** | 沿用既有语义：工具同名**先注册者胜**（market 不绕开）；卸载 jar 即注销其全部工具；`state.json` 卸载时清记录、archive 保留供重装。 |

---

## 2. 目录布局（<配置目录> 下）

```text
<配置目录>/
├── plugins/            # 活跃 jar（现有：PluginManager 扫这里，删除即卸载）
├── market/             # 【新增】本地市场
│   ├── <包名>/         #   可安装插件包（用户拷入即出现在「市场」）
│   │   ├── package.manifest.json   # 见 §3
│   │   └── payload...  #   plugin.jar | mcp-server.json | skills/<name>/...
│   ├── state.json      #   安装记录：name→{format, version, kind, enabled}
│   └── archive/<id>/<version>.<ext>  # 上一活跃版（回滚源，仅存 1 版）
├── skills/             # 【新增】已安装 Skill（<name>/SKILL.md + reference/）
└── mcp.yml             # MCP 服务器（现有；market 装 MCP 即追加到此）
```

打开市场/启动时自动建目录。`market/`、`skills/`、`plugins/` 彼此兄弟目录，market 内 `.loaded`、archive 不做热加载（仅 plugins/ 与 skills 参与运行时）。

## 3. 元数据 manifest 统一结构

`package.manifest.json`（市场包描述，也即未来服务端 CatalogEntry 的本地同构）：

```jsonc
{
  "format": "jar",                 // jar | mcp | skill
  "id": "hello",                   // 稳定唯一 id（= jar 插件 id / mcp 服务器名 / skill 名）
  "name": "Hello 示例",             // 展示名
  "version": "1.2.0",
  "description": "……",
  "author": "……",
  "license": "Apache-2.0",          // 禁 GPL/AGPL 强传染：安装前校验并告警
  "minVersion": "0.1.0",            // OmniForge 最低版本（预留，本批只记不严校验）
  "payload": "payload/hello.jar",   // 包内相对路径
  "tools": ["say_hello"]            // 展示用（jar 可从 manifest 或运行时读）
}
```

jar 内嵌 `META-INF/omniforge-plugin.json`（§1#5）供已安装态/升级比较读取；`PluginManager` 加载时顺带读入并存进加载记录。

## 4. 三格式安装/启停语义

| 格式 | 载荷 | 安装 | 停用/卸载 | 已装态来源 |
|---|---|---|---|---|
| jar | plugin.jar | 备份现活跃版→复制 `plugins/<id>.jar` | 删文件（WatchService 卸载） | `PluginManager` 加载记录 + 内嵌 manifest |
| mcp | mcp-server.json（McpServerConfig） | 读 mcp.yml→追加(或按 id 覆盖)→save+`holder.update` | 置 enabled=false / 从 mcp.yml 移除 | mcp.yml |
| skill | skills/<name>/ 目录 | 复制到 `<配置>/skills/<name>/` | `skills.yml` 启用集移除（目录保留，便于重开） | skills 目录 + `skills.yml` |

**升级/回滚**：三个格式均以「覆盖活跃态 + 保留上一版于 archive」表达（jar 覆盖→MODIFY 自动重载；mcp 覆盖条目→holder 重建；skill 覆盖目录）。
Skills 无版本执行差异问题（同目录覆盖）。

**启用集（skill）热生效**：`SkillSettingsStore(skills.yml) + holder.update`，下次 Agent 调用即并入系统提示（每次 run() 现取，无需重启）。

## 5. 引擎接线（core 改动最小化）

- `core` 新增 `SystemInstructionContributor` SPI（core 无实现，默认空）。
- `ReactAgentLoop` 增加可空构造注入（沿用「新构造 + 旧构造兼容」惯例），`buildMessages` 组装：
  `调用方 systemText` → `contributor.extraSystemText()`（启用 Skills，**有内容才并**）→ `BASE_SYSTEM_PROMPT`（纪律规则仍居末）。
- app 装配层 `SkillContributor implements SystemInstructionContributor`：读 `skills.yml` 启用集 + 拼 SKILL.md 正文（总量上限、越界截断带告警日志）。Bean 经 `AgentAutoConfiguration` ObjectProvider 注入。
- 变更均为 run()/runStreaming() 共用路径一次，不影响 IM/UI/Agent 现有行为（contributor 为 null 时完全不变）。

## 6. 插件市场面板（ui 模块，动作型对话框）

- 主界面导航新增「🧩 插件」按钮（Standalone 模式；企业版隐藏本地插件管理，理由同本地模型控件）。
- `PluginMarketDialog`（沿 RecordDialog/KnowledgeDialog 惯例：左上 ← 返回、标题、主题挂 `ThemeManager.attach`、Toast）：
  - 左栏页签或上分栏：
    - **市场**：扫描 `<配置>/market/*/package.manifest.json` 列出「可安装包」（名称/版本/格式徽标/作者/描述/许可），每行按钮按状态显示 **安装 / 升级 / 回滚 / 已安装✓**；安装确认弹窗明示格式 + 许可 + 工具清单；安装后 `market` 面板即时刷新已装态。
    - **已安装**：三组（jar 插件 / MCP 服务器 / 技能）——jar 显示内嵌 manifest + 运行时工具名清单 + 卸载；MCP 显示名称/传输 + 启停 + 卸载（→mcp.yml）；技能显示 name/description + 勾选启用 + 卸载。
  - 顶部「导入插件包」按钮：文件选择器选目录/zip，解压导入 `market/<id>/`（简化开发者分发为 zip）。
- 交互经 `ui.plugin.PluginBridge` SPI（DTO + 动作）由 app 层 `PluginBridgeImpl` 注入（复用 EnterpriseBridge 模式，避免 ui→app 反向依赖）。
- `market/state.json`、`skills.yml` 由 app 侧 Store 读写（Jackson，先校验后写 all-or-nothing；损坏回退空 + 告警日志——沿用既有 Store 惯例）。

## 7. 安全与合规

- **许可校验**：market 安装前解析 manifest.license，命中 GPL/AGPL 强传染 → 红色拦截并说明原因（贯穿项目禁 GPL 约束）。
- jar/MCP 沿用既有边界：插件类双亲委托复用 common；MCP 仅挂可信服务器（市场内 MCP 描述仍要用户点确认）。
- Skill 指令并入系统提示属「提示注入」面：技能来自用户自装目录，默认仅在 GUI Agent 单模型路径生效；面板给出风险提示文案（第三方 SKILL.md 可影响模型行为）。
- 市场目录操作全程文件级（无新网络面；远程拉取待服务端 API 落地后走 HTTPS+hash 校验，属预留项）。

## 8. 服务端元数据 API（预留，不实现）

- 统一 `PluginCatalogEntry`（同 §3 结构 + `downloadUrl`/`size`/`sha256`）。
- `PluginCatalogSource` SPI：`List<PluginCatalogEntry> list()`。本批实现 `LocalCatalogSource`（扫 market/）。
- 预留（仅文档，不写代码）：enterprise server `GET /api/plugins/catalog` + 管理员审批表，客户端 `RemoteCatalogSource` + 下载校验。PG/服务端环境就绪后按 C 级批次走，更新本文档状态。

## 9. 打包文档（本批交付物之一）

- `docs/PLUGIN_DEV_GUIDE.md`：三类插件作者指南——
  ① jar：依赖 omniforge-common、`OmniForgePlugin` 实现、`META-INF/services` 声明 + 可选 `META-INF/omniforge-plugin.json`、示例 pom + 源码；
  ② skill：目录结构与 SKILL.md frontmatter 约定 + reference 组织建议；
  ③ mcp：mcp-server.json 写法（可照现有 MCP 服务器配置）。
  附 `package.manifest.json` 模板与 zip 打包步骤。

## 10. 测试计划

- core（+3~4）：`SystemInstructionContributor` 拼装单测 ×2（有/无 contributor、越界截断）、PluginManager 读内嵌 manifest 单测 ×1。
- app（market 协调层，+8~10，mock 文件目录）：LocalCatalog 扫描/解析损坏包跳过、jar 安装/备份/升级/回滚（伪文件断言 plugins/ 与 archive/ 内容）、MCP 安装并入 mcp.yml（临时目录 + holder）、skill 安装目录复制、license 拦截（GPL 拒装）、state.json 读写往返、导入 zip 解压。
- ui（+4~6）：Bridge DTO 归一化、Dialog 状态文案切换（安装/升级/已装）纯逻辑类可测部分。
- 全量 `mvn verify` BUILD SUCCESS 后 GUI 冒烟 + 用户验收。

## 11. 本批改动文件预估

- core：`SystemInstructionContributor`(新)、`ReactAgentLoop`(+注入并入拼装)、`PluginManager`(+manifest 读取/详情暴露)、`AgentAutoConfiguration`(接线) —— 小改。
- app：`app.market` 包（MarketManager/Stores/Manifest DTO/LocalCatalogSource/PluginBridgeImpl/SkillContributor）+ `PluginBridgeImpl` 注册。
- ui：`PluginMarketDialog`、导航按钮、`ui.plugin` Bridge SPI。
- docs：本文档 + `PLUGIN_DEV_GUIDE.md` + REQUIREMENTS/TASKS 状态同步。
- 企业仓库：**零改动**（本批纯开源侧；服务端 API 为预留文档）。

## 12. 状态

- [x] 设计确认（用户编号回复 §1：12 项全确认；Skills 截断补充分支 = 整体截断 8000 字 + 日志告警）
- [x] 编码 + 测试全绿（全量 verify：353 用例全绿，2026-09-03）
- [ ] GUI 冒烟 + 用户验收（待用户真机：市场面板安装/升级/回滚 + 技能注入 + MCP 一键装启）
- [x] 提交

## 13. 实施记录（相对本文档的两处细化，2026-09-03）

1. **内嵌 jar manifest 读取在 app 侧**（`MarketPackage.readEmbedded`，JarFile 读
   `META-INF/omniforge-plugin.json`），`PluginManager` 仅新增 `listLoaded()` 运行时详情暴露，
   不解析 manifest——core 改动更小，UI 已装态元数据经 market 读取补全。
2. **技能启用集并入 MarketState（state.json）**，不设独立 skills.yml：`SkillSettings`
   enabled 标记随市场安装记录持久化，`MarketManager.extraSystemText()` 直接读 state 构建注入块。
3. 交付物含 `docs/PLUGIN_DEV_GUIDE.md`（第三方打包指南）。
