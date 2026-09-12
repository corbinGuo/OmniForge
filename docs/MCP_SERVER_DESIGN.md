# OmniForge MCP 服务端化设计（P1-4 重述，2026-09-04 待用户编号确认）

> **背景**：P1-4 原描述「MCP Client + OFT 规范转换器」实际已交付（客户端接入内层服务器 +
> ToolCallback 转换）。本设计把 P1-4 重述为 **反向半环**：把 OmniForge 内置工具以
> **本地 MCP server** 暴露给外部 AI 客户端（Claude Code / Cursor / 其它支持 MCP 的 Agent）
> 直接调用——例如让外部客户端能用 OmniForge 的 file_read_write（含沙箱）、knowledge_search 等。

SDK 实证（mcp-core 0.17.0，客户端 starter 已带入 classpath，零新依赖）：
`io.modelcontextprotocol.server.McpServer` / `McpServerFeatures.SyncToolSpecification` /
`io.modelcontextprotocol.server.transport.StdioServerTransportProvider` 均存在 → **stdio 服务端可落地**。

---

## 1. 决策点（请按编号回复 确认/修改）

| # | 决策 | 建议（默认） |
|---|------|------|
| 1 | **形态/传输** | v1 = **stdio**：新增启动分支 `omniforge --mcp-server`，进程内以 MCP server 运行在 stdin/stdout，直到 stdin EOF 退出。外部客户端 stdio 指向该命令即可调用。**HTTP/SSE 服务端留后续**（Servlet/WebMvc 需容器，桌面本地 v1 先覆盖标准 stdio 场景）。 |
| 2 | **暴露哪些工具** | 取自 `ToolRegistry`（内置 + 插件 + 知识库），**过滤三条**：① 名字以 `mcp_` 开头的内层聚合工具不暴露（避免 double-hop，这些服务器外部可直连）；② `ToolSpec.requiresConfirmation=true`（如 shell_executor）不暴露——stdio 无 UI 可弹确认；③ 受工具开关禁用的（shell/python）不暴露。每次 list 按当前 holder 重算，开关热生效。 |
| 3 | **授权/安全边界** | stdio 绑定调用进程（本机）权限背书，**无网络监听、无鉴权头**；工具执行继承既有约束（file 沙箱根/黑名单/shell 黑名单/网络白名单）。HTTP 版本（后续）再设计 127.0.0.1 + Bearer token。 |
| 4 | **schema 映射** | `ToolSpec.parametersSchema` → 复用「空参/裸 properties 补 object 壳」逻辑（`OmniForgeToolCallback.normalizeSchema` **抽成公共静态工具**共用，避免两份实现漂移）→ MCP `inputSchema`；`ToolResult` → MCP ToolResult（success=text；FAILED/TIMEOUT → isError）。 |
| 5 | **装配/入口** | `OmniForgeLauncher.main` 增加 `--mcp-server` 分支：headless 装配（复用现有 EmptyConfiguration 路径）+ MCP server 专用装配；server 常驻由 stdin 阻塞维持，EOF 即关上下文退出。**日志通道隔离**：stdio 模式下控制台日志必须走 **stderr**（log4j2 Console target 改由系统属性 `omniforge.console.target` 控制，mcp 模式设 SYSTEM_ERR），绝不明文混入 stdout 破坏协议。 |
| 6 | **实现位置** | app 模块（已依赖 tools/knowledge/mcp）：`OmniForgeMcpServer`（构建 stdio server、注册工具、把 MCP call 映射到 Tool SPI）；不新增模块/表。core 仅抽 `schemaObject` 公共工具（小改）。 |
| 7 | **与 GUI/Headless 关系** | 独立进程模式，与 GUI/Headless 平级；不内嵌常驻服务到 GUI 窗口进程（GUI 内嵌 HTTP 服务留后续增量）。文档附 Claude Code/Cursor 的 `.mcp.json` 配置示例。 |
| 8 | **内层 MCP 客户端** | mcp 模式下**不建立**内层 MCP 客户端连接（无 mcp_* 工具需要，省资源/日志噪音）。 |
| 9 | **不做（明确）** | HTTP/SSE、多会话 Web 管理、MCP prompts/resources/根目录能力暴露、内层 mcp_* 透传、鉴权体系、GUI 内嵌常驻服务端。 |

## 2. 数据流

```text
外部 AI 客户端 (stdio)
   └─ spawn: omniforge --mcp-server
        ├─ 装配：EmptyConfiguration(headless) 路径 → ToolRegistry / 沙箱设置 / 工具开关 holder / 知识库
        ├─ OmniForgeMcpServer（McpServer.sync + StdioServerTransportProvider）
        │    ├─ list_tools  → 过滤后工具集（非 mcp_*、免确认、已启用）+ schema
        │    └─ call_tool   → Tool.execute(ToolRequest(arguments, sessionId=null, ctx)) → ToolResult
        └─ 日志 → stderr（stdout 纯协议）
```

- 每个 MCP 请求在新 virtual thread 执行；`ToolRequest.sessionId` 传 MCP 会话（或 null）——
  Tool SPI 不要求 session，保持 null 兼容。
- file_read_write 等仍按**当前沙箱根**（holder 热值）执行，与 GUI 内一致。

## 3. 配置

- `application.yml`：`omniforge.mcp-server.enabled`（默认 false）+ 内部目录沿用现有工具/知识库目录。
- 启动参数：`--mcp-server`（与 `--headless` 互斥；headless 模式加工具集后由 MCP 分支接管）。
- 日志：log4j2.xml 的 Console appender 改 `target="${sys:omniforge.console.target:-SYSTEM_OUT}"`；
  mcp 分支设 `omniforge.console.target=SYSTEM_ERR`，其余日志仍在日志文件。

## 4. 测试计划

- 单测（app 模块，进程内起 server handler + 内存 stdio 传输）：
  ① list_tools 只含「非 mcp_* + 免确认 + 已启用」工具、schema 为 object 壳；
  ② call_tool：file 读写临时沙箱 success；禁用工具不在列表；requiresConfirmation 工具不在列表；
  ③ 非法参数/工具不存在 → isError。
- 自测 CLI：临时写文件测 `omniforge --mcp-server` 可用一个 mcp 客户端直连。
- 全量 `mvn verify` BUILD SUCCESS；用户再以 Claude Code/Cursor 配置真机验证。

## 5. 文档/记录

- 设计来源：工具生态规划「MCP server 暴露内置工具（反向）」。
- PLUGIN_DEV_GUIDE 无需改；可加一节「如何让外部客户端用 OmniForge 工具」（.mcp.json 示例）。

## 6. 状态

- [x] 设计确认（用户 §1 九项 + 两个特别提示全确认）
- [x] 编码 + 测试全绿（app reactor 全套 BUILD SUCCESS；新增 McpToolCatalogTest 3 + OmniForgeMcpServerTest 2）
- [ ] 用户真机（Claude Code/Cursor stdio 指向 omniforge --mcp-server）

## 7. 实施记录（2026-09-04，相对文档的两处实现细化）

1. **stdio 服务端为自研最小协议实现**（非 mcp-core server provider）：`OmniForgeMcpServer` 手写
   JSON-RPC 2.0 + Content-Length 帧，覆盖 initialize / notifications / tools.list / tools.call / ping，
   stdin EOF 退出——传输与生命周期完全自控（SDK provider 的启动语义在该版本不自动读 stdin）。
   SDK 仅用于 Tool/McpSchema 类型与 McpJsonMapper（复用 client starter 已有 classpath）。
2. **内层 MCP 客户端关停**：mcp-server 分支设 `omniforge.mcp.enabled=false`，McpAutoConfiguration 的
   McpClientManager/McpToolRegistrar 增加该属性条件（默认 true，GUI/headless 不变）。
