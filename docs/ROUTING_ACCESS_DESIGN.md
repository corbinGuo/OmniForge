# OmniForge 路由访问设计 v1.0（Pro 智能路由 层级化 + 权限模型分配）

> 状态：设计已确认（2026-09-03，Batch 2 / P1-2），进入编码。
> 关联：docs/PLUGIN_ROUTING_DESIGN.md（双 alias 智能路由前身）。

## 1. Context
把现有基于「简单/高级双 alias」的 `ComplexityModelRouter` 升级为**分层级（tier 1~5，可自定义层名）+ 按本地身份分配路由策略**的 TierAware 路由；提供配置中心 GUI（不再手编 routing.yml）；生效范围 = **直连 + Agent + IM**（辩论保持用户手选不限）。全配置 GUI 化约束延续。

已确认决策：
1. 身份基：本地身份规则。GUI=`os:<user.name>`；IM=`im:<平台>:<发送者>`；通配匹配；未命中→默认策略。企业角色后续对接。
2. 模型档位：自定义层级方案。`ModelConfig.tier`（Integer 1~5，可空=未分级）；层名可自定义；策略按层级比较。
3. 生效范围：直连 / Agent / IM 均按身份策略路由；辩论不限制。

## 2. 类图与代码结构
```
omniforge-core .../gateway/
  ModelConfig.java        [+ Integer tier（1..5，null=未分级）]
  ModelGateway (接口)      [+ String routeAlias(String alias, String identityKey, String userText)]
  DefaultModelGateway     [+ 实现 routeAlias → router.route(合成 GatewayRequest)]
  GatewayRequest          [+ String identityKey]（null=匿名/默认）
router/
  ModelRouter (SPI)                 route(GatewayRequest, ModelGatewayConfig)
  TierAwareRouter                   [替换 ComplexityModelRouter 装配]（决策见 §5）
  AliasModelRouter                  （不变，default 兜底）
  RoutingStrategyType enum          {AUTO,FIXED,RANGE,EXCLUDE}
  RoutingStrategy record            (type,minTier,maxTier,fixedTier,excludeTiers) + static 工厂
  RoutingRule record                (priority,name,identityPattern,strategy)
  RoutingConfig record              (enabled,threshold,weights,keywords,exemplars,
                                     tierNames,defaultStrategy,rules,
                                     legacySimpleAlias,legacyAdvancedAlias)
  RoutingConfigStore                [RoutingSettingsStore 迁移/改名：readTree load + save]
  RouterIdentity                    静态：resolve(request) → "os:x"|"im:p:u"|null（glob 匹配工具）
RouterAutoConfiguration             [bean → TierAwareRouter]
agent/AgentRunRequest               [+ String identityKey]
agent/ReactAgentLoop                [alias=null → modelGateway.routeAlias(alias, identity, text)]
omniforge-gateway ImAgentMessageHandler  [identityKey="im:平台:from"]
omniforge-ui SettingsDialog          [MODULE_ROUTING 模块：层级名/默认策略/规则表]
omniforge-ui OmniForgeApplication   [直连/Agent 传 identityKey="os:user.name"]
omniforge-ui SettingsDialog.ModelCard [tier 选择器：未分级/1..5]
```
```
ModelRouter (interface) ◄── AliasModelRouter
                          ◄── TierAwareRouter ──uses── RoutingConfigStore ──> routing.yml
                               ├─ LicenseService（Pro 门控）
                               ├─ List<RouteScorer>（tokenLength/keyword/embedding）
                               └─ 身份规则匹配（priority 升序，glob）
ModelGateway.resolveAlias / routeAlias → DefaultModelGateway ──router.route──┘
```

## 3. 数据模型与接口定义
```java
// ModelConfig 增补
private Integer tier;                    // 1..5；null=未分级
// getTier/setTier：非 1..5 一律归一为 null（越界丢弃并记录）

public enum RoutingStrategyType { AUTO, FIXED, RANGE, EXCLUDE }

public record RoutingStrategy(RoutingStrategyType type,
        Integer minTier, Integer maxTier,
        Integer fixedTier, List<Integer> excludeTiers) {
    static RoutingStrategy auto(Integer minTier);       // AUTO，minTier null=不限
    static RoutingStrategy fixed(int tier);             // FIXED
    static RoutingStrategy range(int minTier,int maxTier); // RANGE
    static RoutingStrategy exclude(List<Integer> tiers);   // EXCLUDE
    // 紧凑构造按 type 归一化；type 必填
}

public record RoutingRule(int priority, String name,
        String identityPattern, RoutingStrategy strategy) {}

public record RoutingConfig(Boolean enabled, Double threshold,
        Map<String,Double> weights, List<String> keywords, List<String> exemplars,
        Map<Integer,String> tierNames, RoutingStrategy defaultStrategy,
        List<RoutingRule> rules,
        String legacySimpleAlias, String legacyAdvancedAlias) {
    static RoutingConfig defaults();  // 评分配置同旧；tierNames 默认5级；defaultStrategy=auto(null)；rules 空
}
```
身份键规范：
- GUI：`"os:" + System.getProperty("user.name","local")`
- IM：`"im:" + platform + ":" + (from 为空? "default" : from)`
- 请求对象 `GatewayRequest` / `AgentRunRequest` 新增可空 `identityKey`。

## 4. YAML（routing.yml，向后兼容读旧键）
```yaml
enabled: true
threshold: 0.6
weights: { tokenLength: 0.4, keyword: 0.4, embedding: 0.2 }
keywords: [ …旧 14 项… ]
exemplars: [ …旧 2 条… ]
tierNames: { 1: "基础", 2: "标准", 3: "高级", 4: "旗舰", 5: "顶配" }
defaultStrategy: { type: "auto" }          # minTier 缺省=不限
rules:
  - priority: 10
    name: "钉钉管理员组"
    identityPattern: "im:dingtalk:admin_*"
    strategy: { type: "fixed", fixedTier: 5 }
  - priority: 20
    name: "本机用户"
    identityPattern: "os:*"
    strategy: { type: "auto", minTier: 3 }
```
旧键 `simpleAlias/advancedAlias` 仅读作 legacy 兜底，GUI 不再写入。

## 5. TierAwareRouter 决策算法（route(request, config)）
1. `request.alias()` 非空 → 直通。
2. 非 Pro 或 `enabled=false` → `AliasModelRouter` 兜底（default model）。
3. 身份解析（identityKey 空→视为默认）：rules 按 priority 升序做 glob 匹配，首个命中生效；未命中 → `defaultStrategy`。
4. 由 strategy 求允许 tier 集合 T：
   - AUTO：无 minTier → 不限（等价旧全局自动）；有 minTier → `tier≥minTier`
   - FIXED：`{fixedTier}`
   - RANGE：`minTier≤tier≤maxTier`
   - EXCLUDE：1..5 ∖ excludeTiers
5. 候选 = config.models 中 `tier∈T`（null 不入队）。
6. 候选空：
   - 全库无任何 tier 标注 且 legacySimple/advanced 可用 → 走旧双 alias 复杂度（迁移前行为）；
   - 否则 → `config.getDefaultModel()` + log.warn「策略层级无可用模型，已回退默认」。
7. 分档锚点 `low=min tier 候选`、`high=max tier 候选`；low==high → 取该 tier 候选（config 顺序首项）。
8. 否则加权评分（tokenLength/keyword/embedding，权重/阈值，对 request.userText）：`score≥threshold` → high 档，否则 low 档。

示例：
| 需求 | RoutingStrategy |
|---|---|
| 自动路由 minTier=3 | auto(3) |
| 强制 tier=5 | fixed(5) |
| 范围 2~4 | range(2,4) |
| 排除 1,2 | exclude([1,2]) |

## 6. 接线
- `DefaultModelGateway.chat/streamText`：透传 `request.identityKey()` 给 router（route 签名不变，字段在 request）。
- `ModelGateway.routeAlias(alias, identityKey, userText)`：为非 GatewayRequest 场景（Agent/IM）合成 GatewayRequest 后调 router。
- `ReactAgentLoop`：alias 非空→直通；空→`routeAlias(null, identityKey, userText)`。
- `ImAgentMessageHandler.requestOf`：传 `identityKey="im:平台:from"`。
- UI 直连/Agent 传 `identityKey="os:user.name"`。
- 辩论 `sendDebate`/`DebateEngine` 不改（手选）。
- Pro 门控：router 内（非 Pro→默认兜底）；路由面板项与自动路由下拉仅 Pro 可见/生效（沿用现有 isPro 展示逻辑）。

## 7. UI（配置中心 Batch1 模式）
- `SettingsDialog`：`MODULES` 在「模型管理」后插入 `MODULE_ROUTING="路由规则"`；`panels.put(MODULE_ROUTING, buildRoutingPanel())`；snapshotRouting 写 routing.yml；effect「保存后立即生效（无需重启）」。
- 路由规则面板：层级名 5 输入框；默认策略（类型下拉 + 层级字段，随类型显隐）；规则表（priority/name/identityPattern/策略摘要 + 删除/上移下移）；`＋ 添加规则` 弹小表单；`保存`（saveModule 套路）。
- `ModelCard` 增「层级」选择器（未分级/基础/标准/高级/旗舰/顶配）→ `ModelConfig.tier`。
- 热生效：沿用 router 文件 mtime 重载。

## 8. 一致化与迁移
- `RoutingSettingsStore` → `RoutingConfigStore`（改名）；RouterAutoConfiguration、测试同步。
- 路径统一 `defaultConfigDir().resolve("routing.yml")`（Windows %APPDATA%\OmniForge，Linux ~/.omniforge）。
- 旧 routing.yml 无 tier/rules → load 归一化默认 + legacy alias 兜底；旧模型无 tier → null。
- 企业模式按登录角色路由：本次明确不做。

## 9. 测试清单
1. RoutingStrategy/RoutingConfig defaults + 归一化校验。
2. RoutingConfigStore：缺省写模板 / 旧 7 键迁移（保留 legacy + tierNames 默认）/ 往返（含 rules）/ 损坏→defaults。
3. TierAwareRouter：alias 直通；非 Pro/disabled 兜底；身份未命中→default；priority 命中；AUTO/FIXED/RANGE/EXCLUDE 候选集；候选空→(全无 tier→legacy；否则 default+warn)；长短文→low/high 档。
4. DefaultModelGateway.routeAlias 冒烟（注入 router）。
5. ReactAgentLoop：alias=null + identityKey → routeAlias 被调用、结果用于 chatModel（更新既有 stub）。
6. ImAgentMessageHandler：请求含 identityKey=`im:平台:from`（captor）。
7. UI 无单测：编译 + 冒烟清单。

## 10. 执行顺序
1. 设计文档落盘（本文件）→ 确认
2. 后端：ModelConfig.tier + GatewayRequest/AgentRunRequest.identityKey
3. 后端：RoutingStrategy/RoutingRule/RoutingConfig + RoutingConfigStore（迁移）
4. 后端：TierAwareRouter 替换 + ModelGateway.routeAlias + RouterAutoConfiguration
5. 后端：ReactAgentLoop + ImAgentMessageHandler 接线
6. UI：路由规则面板 + 模型卡片 tier 选择器
7. 全量 `mvn verify` + 提交

## 11. 验收映射
| 验收点 | 落点 |
|---|---|
| 模型可设 1~5 / 未分级 | ModelCard tier + ModelConfig.tier |
| 层名自定义 | tierNames GUI |
| 规则增删改/排序/通配 | 路由规则面板规则表 |
| 身份识别 os / im:平台:from | identityKey 接线 |
| 直连/Agent/IM 执行、辩论手选 | §6 |
| 旧配置迁移无 tier | §8 + legacy 兜底 |
| 层级无可用模型→默认+告警 | §5.6 log.warn |
| routing.yml 读写 + 热生效 | RoutingConfigStore + mtime 重载 |
