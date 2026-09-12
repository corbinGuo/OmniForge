# QQ 官方机器人接入设计（2026-09-01，用户已确认 4 项决策）

前置调研：docs/QQ_OFFICIAL_API_RESEARCH.md。用户确认：① WebSocket + Webhook 双通道；② 单聊 + 群@；③ 完整回复（非流式）；④ 提供沙箱/正式环境切换。NapCat（`467811f`）保留共存（platform=qq），官方适配器 platform=**qqofficial**（幂等键隔离）。

## 组件清单（omniforge-gateway/qq 包）

| 组件 | 职责 |
|---|---|
| QqSettings / QqSettingsStore | 配置模型 + Jackson YAML 持久化 `<配置目录>/qq-im.yml` + 运行时热生效（保存即更新） |
| QqAccessTokenProvider | client_credentials 获取 + 内存缓存（到期前 60 秒刷新）+ 配置变更自动失效 |
| QQOfficialAdapter | IMessageAdapter：payload `{id,op,d,s,t}` 解析，仅 op=0 且 t=C2C_MESSAGE_CREATE / GROUP_AT_MESSAGE_CREATE；message_type=0/103 取文本，3（卡片）跳过 |
| QqSignatureVerifier | Webhook Ed25519 验签：op=13 回调验证签名生成（官方向量自测）+ 事件推送验签（msg=event_ts+raw_body） |
| QqWebSocketReceiver | SmartLifecycle：GET /gateway → wss → Hello/Identify/Heartbeat/Resume 状态机 → 事件进 ImMessageRouter；断线 ExponentialBackoff 重连 |
| QqWebhookReceiver | SmartLifecycle：com.sun.net.httpserver 回调端点（端口 80/443/8080/8443 任配）→ op13 验证应答 / op0 验签后进路由 |
| QQOfficialReplySender | ImReplySender：单聊 /v2/users/{openid}/messages、群聊 /v2/groups/{group_openid}/messages；被动回复带 msg_id+msg_seq（同消息递增）；1800 字截断；err_code 校验 |
| QqAutoConfiguration | 装配全部组件（AutoConfiguration.imports 注册）；接收器 start() 时读 settings.enabled 门控 |

## 关键设计决策

1. **platform 标识**：官方 = "qqofficial"；NapCat 保持 "qq"。ImMessageDedup (message_id, platform) 复合唯一天然隔离；ImAgentMessageHandler 按 platform 匹配 ReplySender。
2. **配置存储**：qq-im.yml（Jackson YAML，QqSettingsStore 热生效），与 tools.yml/ui.yml 同模式；Spring @ConfigurationProperties 仅作 application.yml 兜底不引入（enabled 门控在组件 start() 内判断）。
3. **token 刷新**：QqAccessTokenProvider 单例；`Authorization: QQBot {token}`；缓存 key 含 appId+appSecret，配置热切换自动失效；失败退避不阻塞回复路径（回复时缺 token 抛明确错误）。
4. **WebSocket 状态机**：opcode 10 Hello → 2 Identify（intents=33554432 即 1<<25，shard [0,1]）→ READY 记录 session_id → 周期心跳（d=最新 s）；断线先 Resume（6），resume 失败/超时重新 Identify；ExponentialBackoff（common.retry）。
5. **Webhook 验签**（Ed25519，JDK 内置）：seed=appSecret（<32 字节 repeat 2x）；op13 验证：sign(seed, event_ts+plain_token) hex 返回；事件：header X-Signature `ed25519=hex` 对 event_ts+raw_body 验签，失败 400。
6. **上下文**：platformContext = {kind: c2c|group, userOpenid, groupOpenid, msgId}；回复 sender 依 kind 选接口。
7. **UI**：ui POM 新增 gateway 依赖（无环：gateway 不依赖 ui）；SettingsDialog 增「QQ 官方机器人」区段（AppID/AppSecret 密码框/环境下拉/启用开关/Webhook 子配置 + 开放平台帮助文案）。

## 测试计划

- QqSignatureVerifierTest：官方文档向量（secret DG5g3B4j9X2KOErG / ts 1725442341 / plain_token Arq0D5A61EgUu4OxUvOp → 87befc99...）断言 + 篡改拒绝
- QqAccessTokenProviderTest：HttpServer 桩（获取/缓存复用/提前刷新/配置变更失效）
- QQOfficialAdapterTest：c2c / group@ 解析、op≠0 null、卡片跳过、引用消息、缺字段拒绝
- QQOfficialReplySenderTest：单聊/群聊 URL 与请求体（msg_id/msg_seq 递增）、截断、err_code 失败
- QqSettingsStoreTest：读写/默认值/损坏文件容错

## 与 NapCat 共存

配置中心两区段并存（「QQ 官方机器人（推荐）」与「NapCat（技术预览）」）；两者均按 enabled 门控，互不干扰。
