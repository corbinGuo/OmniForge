# 运营中心 UI 设计（审计日志 + 白标品牌，2026-09-05 待用户编号确认）

> 范围（P2-1，用户 2026-09-05 拍板优先级第 1 项）：审计日志可视化查询 + 白标品牌配置 GUI
> （替代手动编辑 branding.yml）。属本地单机桌面功能，UI 走既有「主界面导航按钮 + 模态对话框」模式。
> 设计确认后再编码。

## 现状（实现落点）
- **审计**：`omniforge-core.AuditLogService`（Spring 条件 Bean，`omniforge.audit.enabled`，默认开）。
  存储 `<配置目录>/logs/audit/audit-YYYY-MM-DD.jsonl`，每行一条 `AuditEntry`（timestamp/user/sessionId/runId/
  modelAlias/requestType(agent|chat|debate)/inputText/stopReason/durationMs/inputTokens/outputTokens/
  costUsd/toolCalls[]）。已有 `query(Instant from, Instant to)` → `List<AuditEntry>`（时间升序，损坏行跳过）。
- **白标**：`omniforge-ui.BrandingManager`（静态单例，启动 `init()` 读 `<配置目录>/branding.yml`，生成/删除
  品牌覆盖 `branding-theme.css`；`appName()/logoImage()/themeColorOverrideCssFile()` 供 UI）。
  `BrandingSettingsStore`（Jackson YAML）目前只有 `load`，**无 save**；BrandingManager **无 reload**；
  改品牌目前只能手动编辑 yml + 重启。Logo 每次调用重读文件，主题色经 ThemeManager 三叠 CSS 生效。

## 1. 决策点（请按编号回复 确认/修改）

| # | 决策 | 默认建议 |
|---|------|---------|
| 1 | **入口与形态** | 主界面新增「🧭 运营中心」导航按钮 → 模态对话框（约 1000×680），内含 TabPane：**审计日志** / **品牌白标**。与知识库/插件对话框一致，不挤主界面布局。 |
| 2 | **可见性** | 单机本地始终可见。审计 Bean 未启用（audit.enabled=false）时，审计页签显示「审计未启用」占位与配置提示；白标为本地 branding.yml 编辑（面向分发/企业定制），CE/Pro 均可配。 |
| 3 | **审计时间范围** | 预置 今天 / 近 7 天 / 近 30 天 / 自定义（起止 DatePicker，止默认当日末）。读取走 `AuditLogService.query(from,to)` 在**虚拟线程**执行、完成后 `Platform.runLater` 回填（大范围不卡 UI，期间进度提示）。 |
| 4 | **审计表格与过滤** | TableView 列：时间(本地化) / 类型(agent/chat/debate) / 模型 / 用户 / 会话 / 耗时(ms) / 成本($) / 终止原因 / 输入摘要(截断)。顶部过滤：用户关键词、模型下拉、类型下拉、是否含工具、仅异常(stopReason≠COMPLETED)。分页固定每页 200 行 + 页码。 |
| 5 | **摘要与详情** | 选定范围顶部摘要卡：请求数 / 异常数 / 总成本 / 平均耗时 / Top 模型。选中行 → 下方详情：runId、输入全文（只读可复制）、工具调用序列表（toolName/status/耗时/参数/结果各截断）。摘要与工具序列在当前结果集上 UI 侧聚合。 |
| 6 | **品牌白标编辑与保存即生效** | 字段：应用名（文本，空=回默认 OmniForge）、主题色（ColorPicker + hex 只读校验 #RRGGBB，清空=默认蓝 #2563EB）、Logo 路径（文件选择 png/jpg + 缩略预览 + 清除）。保存 → `BrandingSettingsStore.save(branding.yml)` + `BrandingManager.reload(配置目录)`（新增）→ 当前运行窗口**即时**更新：主题色重挂三叠 CSS、Logo 重读、窗口标题/导航文字改；失败仅 Toast、不改运行态。 |
| 7 | **工程接线** | BrandingManager 增公开 `configDir()` 与 `reload()`；BrandingSettingsStore 增 `save()`；运营中心 `OpsCenterDialog`（ui 包，双 Tab）：审计 Tab 注入 `AuditLogService`（App 装配层经 ApplicationContext 传递，缺 Bean→未启用占位）、品牌 Tab 用 BrandingSettingsStore + BrandingManager.reload；保存品牌后经回调刷新主窗口标题/导航 Logo（OmniForgeApplication 提供 `refreshBranding()`，运行时重设 title 与 Logo 节点）。 |

## 2. 涉及改动（预估）
- `omniforge-ui`：`OpsCenterDialog`（审计 + 白标两 Tab）；主界面新增「🧭 运营中心」按钮与 `refreshBranding()`；
  品牌 Tab 复用现有 `.config-card/.primary` 样式与 Toast；审计 Tab 空态/加载态。
- `omniforge-ui.branding`：`BrandingSettingsStore.save(Path, BrandingSettings)`；
  `BrandingManager`：`configDir()`（把默认配置目录逻辑提为公开方法）、`reload()`（重读 yml + 重建/删除
  branding-theme.css + 供 UI 读取的新 current）。
- `omniforge-core.audit`：**不改**（复用 `query`；如需「自定义近 30 天内超大范围」仅靠 UI 虚拟线程加载）。
- `omniforge-app`：装配层把 `AuditLogService` 注入主窗口/Ops 入口（沿用 knowledgeService 传递模式）。
- 测试：`BrandingSettingsStoreTest` + 保存 roundtrip、损坏文件回默认；`BrandingManager` reload 重建/删除 css 单测；
  审计聚合/详情多为 UI 逻辑，随真机验收（core audit 查询已被 AuditLogServiceTest 覆盖）。

## 3. 不做（v1）
- 审计条目编辑/删除、远程拉取企业审计（企业远程审计属 P2-2/服务端范畴）。
- 超大范围（如整年）内存聚合与全文检索（引入索引库不在本版）；量级警示写在 UI 近 30 天默认。
- 多用户/角色审计权限（P2-2 企业版管理 UI）。
- 品牌字体包/多语言/图片自动缩放处理（Logo 原样显示）。

## 4. 状态

- [x] 设计确认（2026-09-05 用户编号回复：§1 七项全按默认 + 补充：Logo 选择文件按钮、审计 CSV 导出、品牌保存前预览）
- [x] 编码 + 测试全绿（全项目 391 用例全绿；ui branding 单测 +3；2026-09-05）
- [ ] GUI 验证（用户真机：审计查询/过滤/详情/导出 + 品牌保存即生效/预览）
