# MCP Client + OFT 规范转换器设计文档

> 版本 v1.1（2026-08-28）｜状态：已交付（构建全绿，开源 199 用例）
> 设计依据：「工具生态」双轨制（MCP 标准 + OFT 自研规范）
>
> 交付记录：用户 5 项确认（stdio+streamable HTTP / mcp_ 前缀 / 内置优先 / 无测试连接按钮 / 仅日志计量）。
> 构建踩坑：① surefire fork 的 java.class.path 仅为引导 jar（用 surefire.test.class.path）；
> ② Content 为密封接口（匿名实现/mock 均不可，用真实 ImageContent）；③ 协议版本协商——
> 客户端校验服务端返回的 protocolVersion 必须在其支持列表内，假服务器需回显客户端请求的版本
> （硬编码版本导致 McpError: Unsupported protocol version）；④ maven 增量编译偶发不重编测试类
> （clean 重建解决）。

## 1. 背景与目标

需求 4.5 承诺工具生态双轨制：

| 标准 | 需求说明 | 现状 |
|---|---|---|
| MCP（模型上下文协议） | Spring AI Alibaba 原生支持 | ❌ 未接入 |
| OFT（OmniForge Tool Format） | 自研规范，自动转换为各模型格式 | ✅ 已落地：`com.omniforge.common.spi.Tool`（ToolSpec/ToolRequest/ToolResult）即 OFT；`OmniForgeToolCallback` 完成 OFT→各模型工具调用格式的转换 |
| 插件热加载 | plugins/*.jar 动态加载 | 🟢 C 级维持推迟（本轮不做） |

**目标（本轮）**：接入 MCP 标准——用户可以挂载任意 MCP 服务器（本地 stdio 进程或远程 HTTP 服务），其工具经 OFT 转换后与内置工具一起出现在 Agent 工具列表；MCP 工具调用走统一的安全护栏（开关 + 日志）。

## 2. 预检结论（2026-08-28 实测）

| 依赖 | 坐标 | 结果 |
|---|---|---|
| Spring AI MCP 客户端 | `org.springframework.ai:spring-ai-starter-mcp-client:1.1.2` | ✅ aliyun 镜像可用（版本由 spring-ai-bom 管理，同 1.1.2；若 BOM 未管理则显式锁定） |
| SAA MCP starter | `com.alibaba.cloud.ai:spring-ai-alibaba-starter-mcp-client:1.1.2.1` | ❌ 镜像缺失——无需，官方客户端已满足 |
| MCP SDK（传递依赖） | `io.modelcontextprotocol.sdk:mcp`（Apache 2.0） | 随 starter 引入，含 stdio / HTTP 传输 |

**SDK API 实证（1.1.2 源码已解包 target/sdk-src）**：`SyncMcpToolCallback` 桥接 `McpSyncClient + McpSchema.Tool → ToolCallback`；`McpToolUtils.prefixedToolName(...)` 提供多服务器命名前缀；客户端经 `McpClient.sync(transport)` 构建（`StdioClientTransport` / `StreamableHttpClientTransport`）；`McpToolsChangedEvent` 事件通知工具列表变化。

## 3. 范围界定

- **模块**：无新模块——在 omniforge-tools 内新增 `com.omniforge.tools.mcp` 子包（需求文档模块树 §4.5 原定位即 omniforge-tools）
- **ER**：无新表
- **依赖**：新增 spring-ai-starter-mcp-client 1.1.2（Apache 2.0，无 GPL 风险）
- **不做**：插件热加载（C 级推迟）、MCP 服务器市场/发现、OFT 对非模型格式的转换器（当前模型侧仅 ToolCallback 一种，已覆盖）

## 4. 核心设计

### 4.1 配置模型与持久化（mcp.yml + 热生效）

```java
/** 单个 MCP 服务器配置 */
public record McpServerConfig(
    String name,              // 唯一标识（工具前缀来源）
    String transport,         // "stdio" | "http"
    String command,           // stdio：可执行文件（如 npx）
    List<String> args,        // stdio：启动参数
    String url,               // http：服务端点（streamable HTTP）
    boolean enabled           // 每服务器独立开关，默认 false（安全底线）
) {}

/** mcp.yml 聚合 + 运行时持有器（模式同 tools.yml/context.yml） */
public record McpSettings(List<McpServerConfig> servers) {}
public class McpSettingsHolder { AtomicReference<McpSettings> ... }  // 热生效
public class McpSettingsStore { Jackson YAML 读写 mcp.yml }
```

配置目录：与 models.yml / tools.yml / context.yml 同目录（`<配置目录>/mcp.yml`）。

### 4.2 客户端生命周期（McpClientManager，SmartLifecycle）

- 每个启用服务器维护一个 `McpSyncClient`：
  - stdio → `StdioClientTransport`（ProcessBuilder 启动子进程，stdin/stdout 通信）
  - http → `StreamableHttpClientTransport`（远程服务端点）
- **韧性**：启动连接失败仅告警不阻断应用启动（0 服务器可用 = 0 个 MCP 工具，与 0 模型韧性一致）；连接失败/断开后**指数退避重连**（复用本轮已交付的 `ExponentialBackoff`，基础 30s、封顶 600s、±20% 抖动、成功复位）
- **热生效**：McpSettingsHolder 变化 → 重建服务器连接（停止旧客户端进程、按新配置启动）
- **工具列表变化**：监听 `McpToolsChangedEvent` → 刷新注册表
- 关闭（stop）：优雅关闭全部客户端与 stdio 子进程

### 4.3 OFT 转换（双轨制落点）

```
MCP 服务器工具 ──McpToolAdapter──▶ OFT（Tool SPI）──OmniForgeToolCallback（既有）──▶ 模型工具调用格式
```

- **元数据转换**（MCP Tool → ToolSpec）：
  - name → `mcp_<server>_<tool>`（McpToolUtils 前缀逻辑，避免与内置工具/多服务器冲突）
  - description 原样；inputSchema（JSON Schema）原样透传为 parametersSchema
  - requiresConfirmation = false（MCP 工具无本地人工确认机制）
- **执行转换**（McpToolAdapter.execute）：
  - `CallToolRequest(tool.name(), arguments)` → `CallToolResult`
  - 结果拼接：TextContent 顺序拼接；ImageContent/EmbeddedResource 以占位说明（如 `[图片：image/png]`，不落盘）
  - `isError=true` → ToolResult.FAILED（错误文本透传）
  - 异常（连接断开/超时）→ FAILED + 明确错误信息，不中断 Agent 循环
- **模型侧**：无需新代码——既有 OmniForgeToolCallback 即"OFT 自动转换为各模型格式"

### 4.4 注册表接入

- `McpAutoConfiguration`（omniforge-tools，afterName=AgentAutoConfiguration）：
  1. 构建 McpClientManager（读 McpSettingsHolder + GatewayProperties 配置目录）
  2. 连接成功后把各服务器工具经 McpToolAdapter 逐个 `ToolRegistry.register(...)`
- **冲突策略**：`DefaultToolRegistry` 保留先注册者 → 内置工具与 MCP 同名时**内置优先**（MCP 让位并告警，安全预期）；`mcp_` 前缀使冲突几乎不可能

### 4.5 安全

- 全部服务器默认 disabled（显式开启才连接）
- **重要提示**：MCP 工具由外部服务器执行，**不受本地沙箱/黑名单约束**——配置中心区段固定展示警示文案（"仅挂载可信的 MCP 服务器"）
- 所有 MCP 工具调用全量日志（服务器/工具名/参数摘要），遵循日志无明文密钥底线
- 参数不做黑名单拦截（服务器自担执行语义）

## 5. GUI（配置中心「MCP 服务器」区段）

- 卡片列表（风格同提供商卡片）：名称 / 传输方式下拉（stdio·本地进程 / http·远程服务）/ stdio 显示"命令 + 参数"两输入框、http 显示"URL"输入框 / 每卡片「启用」复选框与「删除」按钮
- 顶部「＋ 添加 MCP 服务器」；区段标题下固定安全警示文案
- 「保存并热重载」时：写 mcp.yml + McpSettingsHolder.update（客户端重建、注册表刷新，无需重启）
- 「测试连接」按钮：可选（待确认 #4）

## 6. 测试计划

1. `McpSettingsStoreTest`：mcp.yml 读写/缺失默认值/损坏回退
2. `McpServerConfigTest`：校验（stdio 必须有 command、http 必须有 url）
3. `McpToolAdapterTest`：**进程内假 MCP 服务器**（stdio 传输，Java 测试服务器实现 JSON-RPC tools/list + tools/call）——元数据转换断言（前缀命名/Schema 透传）、执行成功/失败（isError）/连接断开
4. `McpClientManagerTest`：连接失败不抛异常（0 工具）、禁用服务器不连接、重连退避调用（注入假退避）
5. `McpToolProvider/AutoConfiguration 集成`：装配冒烟（假服务器工具出现在 ToolRegistry、内置优先冲突策略）

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| MCP 协议版本演进（2024-11-05 → 2025-06-18） | 依赖 SDK 官方客户端协议处理，不自研 JSON-RPC；升级随 Spring AI 版本线 |
| stdio 子进程僵尸/泄漏 | SmartLifecycle stop 时销毁进程；断连检测触发重启 |
| MCP 服务器工具数量/名称失控 | 前缀命名 + 每服务器开关 + 全量日志 |
| 远程 HTTP MCP 的 SSRF | 不做 URL 黑名单拦截（用户显式配置即信任）；日志记录端点 |

## 8. 待确认事项

1. 传输方式本轮支持 **stdio + streamable HTTP**（HTTP SSE 旧传输推迟）——确认？
2. 工具命名前缀 **`mcp_<服务器名>_<工具名>`**——确认？
3. 冲突策略：**内置工具优先，MCP 让位并告警**——确认？
4. 配置中心是否要「**测试连接**」按钮？（建议：本期不做，靠保存后状态栏反馈连接结果）
5. MCP 工具调用**不做成本计量**（外部服务器消耗未知，仅日志）——确认？
