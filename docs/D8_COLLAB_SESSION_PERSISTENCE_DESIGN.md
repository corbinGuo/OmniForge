# D8：协作内容写入会话记录设计（D8_COLLAB_SESSION_PERSISTENCE_DESIGN）

> 状态：**已确认 · 已交付**（2026-09-13，Q1-Q5 全部采纳推荐项；开源 `a381acc`/企业本地；真机验收通过——含侧栏实时刷新（强制重拉修复 ec5c92e）与单击打开两轮反馈修复）
> 最后更新：2026-09-13
> **基线：开源仓库 `f186ae7` / 企业仓库 `e4f3f31`**（已核对）
> 分工：设计窗口产出，确认后实施

## 一、背景（用户反馈，2026-09-13）

「这种方式并没有在对话中形成记录」——协作各阶段（①每模型陈述/②结论/⏸/③执行/④验收）目前是**客户端内存气泡**（D1-D4 设计），只有 🗂 档案存 run 快照。切会话/重启后**会话流里的协作内容全部消失**，与「对话即数据资产」定位冲突。

## 二、现状（代码事实）

| 事实 | 位置 |
|---|---|
| 企业会话消息：`SessionMessage(id, session, role(16), content(4000), createdAt)`；GET /api/sessions/{id}/messages 返回 {role, content, createdAt} | SessionMessage.java / SessionController.java:57 |
| 普通对话自动落库（user+assistant）；**协作阶段零落库** | ChatController |
| D5 后 RunDto 含 sessionId；D7 修复：collabCreate 无会话时服务端自动建会话并挂接 | CollabService.java |
| 客户端恢复：按 role=user/assistant 渲染；collab 角色不存在 | 恢复会话路径 |
| content 上限 4000 字符（PG varchar(4000)）；执行输出可能超长 | 实体注解 |

## 三、方案

### 3.1 服务端（企业仓库）

1. **新端点** `POST /api/sessions/{id}/messages`：body `{role, content}`；role 仅接受 `collab`（user/assistant 仍由 chat 路径自动落库，不接受外部写入）；归属校验 + **viewer 403**（只读角色不得写）。返回落库后的 MessageDto。
2. **content 上限**：实体改 `@Column(columnDefinition = "text")`（新库直接 text；既有验收库执行一次 `ALTER TABLE session_message ALTER COLUMN content TYPE text`——本地一次 SQL，已纳实施清单）；客户端仍按 ≤3600 字符/条分片，超长执行输出拆多条（`part:i/n` 元数据），恢复时按序拼接。
3. role=collab 的 content 约定（Q1-A 结构化 JSON）：
   `{"kind":"statement|stage|checkpoint","alias":null|"模型别名","round":0|N,"text":"..."}`
   - statement = 讨论陈述（alias/round 填充）→ 恢复渲染色点气泡
   - stage = ②结论/③执行/④验收（text 全文）→ 恢复渲染为系统样式文本
   - checkpoint = ⏸ 暂停（仅记录事实，恢复时不复活按钮——B1 设计既定）

### 3.2 客户端（开源仓库）

1. `EnterpriseBridge` +`appendSessionMessage(sessionId, role, content)`；Impl 走新端点。
2. **协作流每个阶段渲染时同步落库**（fire-and-forget 虚拟线程，失败仅 WARN 不中断协作流 Q5-A）：
   - ① 每模型陈述 → kind=statement（alias/round/text 全文）
   - ②结论 / ③执行 / ④验收 → kind=stage
   - ⏸ 暂停 → kind=checkpoint
3. **会话采用（Q4-A）**：collabCreate 返回后，若当前无会话且 run 带 sessionId → 采纳为当前会话 + 刷新侧栏（新会话立即出现在左侧列表，无需重登）。
4. **恢复渲染（Q3-A）**：restore 会话时 role=collab 的消息按结构化还原——statement → 色点气泡（别名头像全文）；stage/checkpoint → 系统样式文本；JSON 解析失败回退平文本系统消息。
5. 旧会话（无 collab 消息）行为不变。

### 3.3 边界

- 单机版不涉及（单机无协作；辩论已有 DebateRecord 落库）
- 🗂 档案保留（run 快照与重试能力），会话记录是「阅读流」，二者并存
- viewer 不落库（只读角色无协作写路径）

## 四、改动清单

**企业仓库**：SessionMessage 实体 content→text + 本地验收库 ALTER 一次；SessionController +POST messages（role 白名单 collab + viewer 拒写 + 归属校验）；测试（落库/白名单/403）≈3
**开源仓库**：EnterpriseBridge/Impl +appendSessionMessage；协作流五处落库调用（虚拟线程 fire-and-forget）；会话采用（collabCreate 后）；恢复渲染 collab 分支；`ui.collab.CollabStageMessage` 纯函数（JSON 组装/解析，单位测 ≈6）

## 五、决策点（编号回复，A/B）

- **Q1** 存储形态：**A** role=collab + 结构化 JSON content（恢复还原色点气泡，推荐）/ B 平文本（恢复为普通文本，丢别名/轮次）
- **Q2** 范围：**A** 仅企业版（单机无协作场景，推荐）/ B 连单机辩论流也改造
- **Q3** 恢复渲染：**A** 结构化还原色点气泡（推荐）/ B 全部平文本
- **Q4** 会话采用：**A** collabCreate 后采纳新会话为当前会话并刷新侧栏（推荐，新会话立即可见）/ B 维持现状（重登后可见）
- **Q5** 落库失败：**A** 仅 WARN 不中断协作流（推荐）/ B 阻断并提示

## 六、真机验收清单（交付后执行）

1. 新会话 @发起协作 → 各阶段上屏同时侧栏出现新会话
2. 切到其他会话再切回 → 协作内容原样还原（色点气泡 + 分隔 + 结论/执行/验收全文）
3. 重启客户端 → 登录 → 恢复会话 → 协作记录仍在
4. 超长执行输出分片落库 → 恢复后完整拼接
5. viewer 账号发起协作 → 无写路径（协作按钮本就不可见）
6. 档案功能回归不变

确认后：更新本文状态为「已确认」→ 实施（提交建议：feat: persist collab stages into session history (D8)）。
