# QQ 机器人官方 API 调研报告（2026-09-01）

来源：QQ 机器人开放平台官方文档 https://bot.q.qq.com/wiki/develop/api-v2/（页面抓取时间 2026-09-01）。
目的：为 OmniForge 从 NapCat（OneBot 11）转向官方 API 接入提供依据。

## 1. 凭证（access_token）

| 项 | 值 |
|---|---|
| 接口 | `POST https://api.bot.qq.com/app/getAppAccessToken` |
| 请求体 | `{"appId": "...", "clientSecret": "..."}`（JSON，client_credentials 模式） |
| 响应 | `{"access_token": "...", "expires_in": 7200}` |
| 有效期 | 7200 秒（2 小时） |
| 刷新规则 | 有效期内重复获取返回相同值；到期前 60 秒内获取返回新 token（旧 token 60 秒内仍有效）；官方建议服务端定时刷新 |
| 鉴权头 | **`Authorization: QQBot {ACCESS_TOKEN}`**（非 Bearer） |
| 错误码 | 100001 限流 / 100007 appid 无效 / 100016 secret 错 / 10004 机器人不存在 |

## 2. 发送消息（统一地址 https://api.bot.qq.com）

| 场景 | 接口 | 频率限制 |
|---|---|---|
| 单聊 | `POST /v2/users/{user_openid}/messages` | 100 QPS |
| 群聊 | `POST /v2/groups/{group_openid}/messages` | 60/qpm（未认证 30/qpm） |
| 单聊流式 | `POST /v2/users/{user_openid}/stream_messages` | — |

请求体核心字段：
- `msg_type`：0=文本(content) / 2=Markdown(markdown) / 6=输入中状态 / 7=富媒体(media)
- `msg_id`：**被动回复**时填事件 `d.id`（单聊 60 分钟 / 群聊 5 分钟内有效；单聊每条最多回复 4 次、群聊 5 次）
- `msg_seq`：与 msg_id 联合防重复发送，默认 1
- `event_id`：与 msg_id 二选一的被动回复方式

响应：成功 `{"id": "ROBOT1.0_xxx", "timestamp": ...}`；失败 `{"err_code", "message", "trace_id"}`（以 err_code 判定，message 可能调整）。
关键错误码：304018 SESSION_NOT_EXIST（机器人未连网关）、304026/304027 msg_id 错误/过期、1100101 安全打击。

## 3. 接收消息：WebSocket（官方主推，桌面应用唯一可行）

- 接入点：`GET /gateway` → `{"url": "wss://api.bot.qq.com/websocket/"}`（带分片版 `GET /gateway/bot` 返回建议 shard 数与并发上限）
- 报文结构：`{"id", "op", "d", "s", "t"}`
- 连接流程：
  1. 连接后收 **opcode 10 Hello**（`d.heartbeat_interval` 毫秒）
  2. 发 **opcode 2 Identify**：`{"token": "QQBot {token}", "intents": 33554432, "shard": [0,1], "properties": {...}}`
  3. 收 **opcode 0 Dispatch** `t=READY`（含 `session_id`）
  4. 按周期发 **opcode 1 Heartbeat**（d=最新 s），收 opcode 11 ACK
- 断线重连：**opcode 6 Resume**（token/session_id/seq），补发遗漏事件后 `t=RESUMED`；resume 失败（4006 等）重新 Identify
- 错误码：4009 连接过期可 resume；4914 机器人下架 / 4915 封禁不可连
- **无签名校验**（鉴权靠 wss + token）

## 4. Webhook（备选，不适合桌面应用）

- 需 HTTPS 公网回调地址，端口仅允许 **80/443/8080/8443**；管理端配置回调与监听事件
- 签名验证：**Ed25519**（botSecret 作 seed 扩展至 SeedSize 签名 plain_token+event_ts 等）——与现有钉钉/飞书 HMAC-SHA256 模式不同，需新实现（JDK 内置 Ed25519 支持）
- 结论：桌面/内网部署不适用，本轮不实现（记录备选）

## 5. 事件（Intents）

`intents` 位标记：**GROUP_AND_C2C_EVENT = 1 << 25 = 33554432**，一个位覆盖单聊 + 群@ + 好友事件。

| 事件 t | 触发 | 关键字段 |
|---|---|---|
| C2C_MESSAGE_CREATE | 用户单聊发消息 | `d.id`（回复用）、`d.author.user_openid`、`d.content`、`d.message_type`（0 文本/3 卡片/103 引用）、`d.message_scene.ext`（msg_idx 等） |
| GROUP_AT_MESSAGE_CREATE | 群内@机器人 | 同上 + `d.group_openid`、`d.author.member_openid`；**content 已自动去除@前缀** |

- **去重**：相同 `msg_id` 可能重复推送，官方要求结合 `message_scene.ext` 的 `msg_idx` 去重——与 IM_MESSAGE_DEDUP 表（message_id+platform 复合唯一）天然匹配，message_id 取 `d.id`。
- 群消息事件 GROUP_MESSAGE_CREATE（非@的群全部消息）同为 1<<25 位下事件，可选接收。

## 6. 与 Phase 3 IM 骨架的映射

| 组件 | 设计 |
|---|---|
| QQOfficialAdapter | implements IMessageAdapter；parse：payload JSON（含 op/t/s 外层）→ 仅处理 op=0 且 t=C2C_MESSAGE_CREATE / GROUP_AT_MESSAGE_CREATE → ImInboundMessage（messageId=d.id、platform=qq、platformContext=回发地址：user_openid 或 group_openid） |
| QqAccessTokenProvider | client_credentials 获取 + 内存缓存 + 定时刷新（到期前 60 秒，ScheduledExecutorService，复用 ExponentialBackoff） |
| QqWebSocketReceiver | SmartLifecycle：getGateway → wss 连接（JDK HttpClient WebSocket）→ Hello/Identify/Heartbeat/Resume 状态机 → dispatch 转 router.route；GUI/Headless 通用，qq.enabled 门控，连接失败指数退避 |
| QQOfficialReplySender | ImReplySender：单聊/群聊发消息（被动回复带 msg_id），截断追加「（消息过长已截断）」，err_code 校验 |
| 幂等/白名单/队列/AI 脚注/Agent 触发 | 完全复用 ImMessageRouter / ImWhitelist / ImMessageDedup / ImAgentMessageHandler，零改动 |
| 配置 | `omniforge.im.qq`：enabled / app-id / app-secret / 环境（正式/沙箱）；配置中心「QQ 机器人」区段（AppSecret 支持加密存储） |

## 7. 待确认设计点

1. 接收方式：WebSocket 为唯一实现（Webhook 因公网+端口限制不做）？
2. 消息范围：单聊 + 群@（与 NapCat 版一致）？
3. 回复方式：统一完整回复（截断+AI 脚注）还是启用官方单聊流式接口？
4. 沙箱环境：是否在配置中提供「环境」选项（沙箱用于未上架机器人联调）？
