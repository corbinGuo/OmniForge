# 插件热加载 + Pro 智能路由设计（C-tier 批次 3，2026-08-29 用户确认）

> 批次 2 已验收通过。本文档记录批次 3 的 5 项设计确认与实现决策，随实现更新。

## 1. 设计确认（用户提出，Claude 逐项确认）

| # | 确认项 | 结论 |
|---|--------|------|
| 1 | 插件扫描目录 `<配置目录>/plugins/` | ✅ 确认（Windows `%APPDATA%\OmniForge\plugins`，Linux `~/.omniforge/plugins`） |
| 2 | 启动时扫描 + WatchService 目录变化自动热加载 | ✅ 确认（防抖 300ms，模式同 ModelConfigWatcher） |
| 3 | jar 删除后自动卸载对应工具（ToolRegistry.unregister） | ✅ 确认 + 实现修正（见 §2 文件锁） |
| 4 | 用户启用 Pro 后自动判断是否路由；手动选模型时路由不生效 | ✅ 确认（UI 落地：模型下拉新增「🤖 自动路由」选项，仅 Pro 显示） |
| 5 | 简单问题 → 便宜模型，复杂问题 → 昂贵模型；routing.yml 定义规则 | ✅ 确认（三信号打分 + 阈值；routing.yml 带默认模板） |

## 2. 插件热加载（omniforge-tools 预留接口 → 落地 core.agent.plugin）

- **OmniForgePlugin SPI 入 omniforge-common.spi**：插件作者只需依赖 omniforge-common（`getName()` / `getTools()`），无需依赖 core；`ToolRegistry.register/unregister` 为既有预留接口，直接沿用。
- **实现位置**：`com.omniforge.core.agent.plugin`——PluginManager 需注入 ToolRegistry（core 内，无循环依赖）。
- **Windows 文件锁修正**：URLClassLoader 持有 jar 打开句柄，Windows 下用户无法删除/覆盖已加载的 jar。方案：jar 先复制到 `plugins/.loaded/<文件名>-<hash>.jar` 从副本加载；原文件可自由增删改。ENTRY_CREATE/MODIFY →（先卸载旧版再）加载；ENTRY_DELETE → 卸载对应工具 + 关闭类加载器 + 删除副本。`.loaded/` 子目录事件过滤忽略。
- **同名冲突**：沿用 ToolRegistry 约定——重名保留先注册者；插件重载时先 unregister 旧工具再注册新版。
- **类加载**：URLClassLoader 双亲委托（parent-first），插件复用应用类路径上的 omniforge-common（避免类版本分裂）；卸载时 close() + 置空引用（类元数据待 GC，Java 插件加载通用约束，Javadoc 说明）。
- **生命周期**：PluginManager 为 SmartLifecycle Bean（启动扫描在 ToolRegistry 就绪后；stop 时卸载全部 + 关闭 watcher）。watch 线程为守护线程。

## 3. Pro 智能路由（core.gateway.router）

- **SPI 落地**：`ComplexityModelRouter implements ModelRouter`（既有 SPI，`route(request, config)` 返回别名）。以 `@ConditionalOnMissingBean(ModelRouter.class)` 注册，替代默认 AliasModelRouter；**非 Pro 或 routing 禁用时内部回退别名直路由**（request.alias() 或 default-model），行为与 CE 完全一致。
- **复杂度三信号**（RouteScorer 接口，加权求和 → 阈值判断）：
  1. TokenLengthScorer：输入 token 估计（复用 context.TokenEstimator 算法思路，归一化 0~1）
  2. KeywordScorer：routing.yml 复杂关键词表（"分析/对比/设计/代码/数学/…"）命中计数归一化
  3. EmbeddingSimilarityScorer：与复杂问题样本集（exemplars，routing.yml 配置）的余弦相似度——经 `RouteEmbeddingProvider` SPI 注入（app 装配层接 knowledge EmbeddingEngine，core 不依赖 knowledge）；provider 缺失/引擎未就绪 → 该信号跳过，权重自动重归一化
- **routing.yml**（RoutingSettings + Store + Holder 热生效，模式同 tools.yml/context.yml）：
  ```yaml
  enabled: true
  simpleAlias: deepseek-chat      # 简单问题 → 便宜模型
  advancedAlias: gpt-4o-mini      # 复杂问题 → 高级模型
  threshold: 0.6                  # 复杂度分 ≥ 阈值 → advanced
  weights: {tokenLength: 0.4, keyword: 0.4, embedding: 0.2}
  keywords: [分析, 对比, 设计方案, ...]
  exemplars: [多因素分析与权衡……, ...]   # 复杂问题样本
  ```
- **License 门控**：`LicenseService.isPro()` 为 false → 路由禁用（回退直路由），UI 不显示自动路由选项。
- **网关接线**：`DefaultModelGateway` 在 `alias == null`（UI 自动路由 / 未指定）时调用 router.route；alias 明确指定 → 直通（路由不生效，符合确认 #4）。
- **UI**：模型下拉首项「🤖 自动路由（Pro）」（仅 Pro 可见），选中后请求 alias=null。
- **明确不做（用户边界）**：插件市场 UI、路由规则 UI 配置——routing.yml 本轮靠默认模板 + 文档说明，GUI 化留待后续（与 v5.3 全配置 GUI 化原则的缺口在交付报告明示）。

## 4. 测试计划

- 插件：测试源码内置 TestHelloPlugin → 运行时打包 jar → 验证加载注册/目录变化热加载/删除卸载/同名冲突/损坏 jar 跳过；PluginJarLoader staging 单测。
- 路由：三信号打分单测、阈值决策、CE 回退、routing.yml 读写往返、网关 alias=null 走路由 / alias 指定直通、权重重归一化。
- 全项目 verify 全绿 + GUI 冒烟（插件目录放样例 jar 验证工具出现在 Agent 模式、自动路由选项出现）。

## 5. 状态

- [ ] 3a 插件热加载
- [ ] 3b Pro 智能路由
- [ ] 编译验证 + 真机冒烟 + 用户验收
