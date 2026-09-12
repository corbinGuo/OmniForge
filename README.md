# OmniForge 社区版（OmniForge Core）

**跨平台、多模型协作的本地优先 AI 工作台。** 一个对话窗口背后是一支模型团队：统一接入多家大模型，支持单模型 Agent、多模型辩论与讨论、本地向量知识库、IM 消息机器人接入、MCP 与插件生态。社区版以 Apache 2.0 开源，全部数据与密钥保存在本机。

- 语言/运行时：Java 21（Temurin LTS）+ JavaFX 21
- 应用框架：Spring Boot 3.5 + Spring AI 1.1 + Spring AI Alibaba 1.1
- 许可证：Apache License 2.0

---

## 功能全景

### 模型网关与对话
- **多提供商统一接入**：DashScope（通义千问）与任意 OpenAI 兼容端点（DeepSeek、Moonshot、OpenAI、Ollama 本地模型等），图形化配置中心，密钥支持环境变量 / AES-GCM 加密存储 / 明文三种来源
- **热重载**：`models.yml` 修改即时生效（防抖监听），无需重启；base-url 写法自动纠偏
- **成本计量**：逐模型 token 用量与估算成本，状态栏实时可见，辩论预算熔断
- **故障韧性**：单模型超时熔断不影响其他模型；默认模型失败自动按序切换可用模型
- **多轮上下文管理**：按会话隔离的记忆、token 预算自动裁剪、可选历史摘要压缩、LRU+TTL 淘汰

### Agent 与多模型协作
- **Agent 模式（ReAct）**：自研 ReAct 循环，思考 → 行动 → 观察步骤卡片逐项可视，支持流式输出、随时停止、单工具中止
- **工具执行人工确认（HITL）**：文件写入/删除、Shell 等危险操作执行前弹窗确认（60 秒超时视为拒绝），Headless/IM 场景自动放行并记录告警
- **多模型辩论**：辩论 / 圆桌讨论 / 头脑风暴三种模式，2~10 个模型同场；无裁判（共识检测 + 观点单调性熔断）与有裁判（结构化裁决、获胜方/死锁判定）两种终止机制；逐 token 实时流式
- **分层模型路由**：模型按 1~5 层级分级，按用户身份（本机用户 / IM 来源）与问题复杂度（长度 + 关键词 + 语义相似度评分）自动选择层级，规则热重载

### 知识库（RAG）
- **本地 Embedding**：DJL + ONNX（all-MiniLM-L6-v2）首启自动下载，支持 hf-mirror 加速与 Ollama 远程模型替代
- **SQLite 向量库**：零外部服务依赖，WAL 模式，重启持久化
- **多格式解析**：PDF / Word(doc/docx) / Excel / PPT / HTML / RTF / 纯文本（含 GBK 兜底），上传护栏（单文件 10MB / 单次 100MB / 50 切片）
- **分类管理**：按目录自动归类、面板分组折叠、整类操作、检索按分类过滤

### 工具与插件生态
- **内置工具**：联网搜索（Tavily / SearXNG）、文件读写（多沙箱根目录白名单）、Python 解释器、Shell 执行器（默认禁用 + 黑名单）、当前时间
- **MCP 双向**：作为 Client 连接任意 MCP 服务器（stdio / streamable HTTP，断线指数退避重连）；作为 Server 将内置工具经 stdio 暴露给 Claude Code / Cursor 等外部 AI 客户端
- **插件热加载**：`plugins/*.jar` 增删即生效（WatchService 防抖），坏插件故障隔离不拖垮主程序
- **插件市场**：本地目录市场，纳管 jar 插件 / Agent 技能（SKILL.md 注入系统提示）/ MCP 服务器三格式，支持安装 / 升级 / 回滚 / 启停

### IM 消息网关
- **六平台接入**：邮件（IMAP 轮询 + SMTP 回复）、钉钉、飞书、企业微信、QQ 官方机器人（WebSocket + Webhook 双通道，Ed25519 验签）、NapCat QQ（OneBot，技术预览）
- **企业级消息语义**：消息幂等去重、白名单权限、签名校验、断线指数退避、AI 生成内容脚注合规
- **对话能力直通**：IM 消息触发单模型 Agent（全量工具）或「辩论：」前缀多模型辩论，回复自动落库

### 数据安全与合规
- **密钥加密存储**：AES-GCM + 本机密钥库，配置文件不留明文
- **沙箱与黑名单**：文件工具多根目录白名单 + 路径逃逸防护；Shell/Python 黑名单 + 默认禁用
- **数据保留策略**：会话 / IM / 工具日志 / 审计四类数据按天数自动清理（默认关闭），一键清理与一键备份
- **升级自动备份**：数据库 schema 版本标记，升级自动备份主库 + 向量库（保留 3 份轮换）
- **审计日志**：按天 JSONL 落盘（用户 / 模型 / 请求 / 成本 / 工具序列），运营中心可视化查询 + CSV 导出
- **EULA 首启签署**：GUI 弹窗 / Headless 环境变量两种确认方式

### 桌面体验
- **JavaFX 深色 / 明亮主题**一键切换（品牌色令牌化，跟随白标）
- **系统托盘**：关闭即最小化到托盘、自绘右键菜单（主题跟随）
- **历史会话侧栏**：日期分组树、搜索、重命名 / 导出 / 删除、切换会话即切换记忆
- **白标**：应用名 / Logo / 主题色自定义（品牌级部署）
- **运营中心**：审计查询可视化 + 品牌配置即时预览生效

### 运行形态
- **GUI 桌面版**：主形态，开箱即用
- **Headless 服务模式**：`--headless` 无界面运行，`/health` 健康检查 + Prometheus `/metrics`，附 systemd 服务模板
- **MCP Server 模式**：`--mcp-server` 以 stdio 将工具暴露给外部 AI 客户端
- **企业版客户端**：`--mode=enterprise` 连接企业版服务端（闭源商业版，多租户 / 协作 / 限额 / 管理后台）

---

## 模块结构

| 模块 | 职责 |
|------|------|
| omniforge-common | Tool SPI 契约、事件总线、异常体系、AES-GCM 加密、敏感掩码、重试组件 |
| omniforge-core | 模型网关、ReAct Agent、辩论引擎、分层路由、上下文管理、持久化（SQLite）、数据保留与备份、审计 |
| omniforge-tools | 内置工具、MCP Client、插件热加载（含 Python 扩展 profile） |
| omniforge-knowledge | 向量知识库：ONNX Embedding、SQLite 向量库、文档解析、语义分块 |
| omniforge-gateway | IM 网关：邮件 / 钉钉 / 飞书 / 企微 / QQ 官方 / NapCat 适配器 |
| omniforge-ui | JavaFX 桌面界面：对话、配置中心、知识库面板、记录、运营中心、主题、托盘 |
| omniforge-app | 可执行装配：GUI / Headless / MCP Server 三入口、Spring 装配、jpackage 打包 |

架构细节（依赖图、ER 图、依赖清单及协议风险标记）见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 快速开始

要求：JDK 21+（推荐 Eclipse Temurin）、Maven 3.9+。

```bash
# 构建并跑全部测试（约 430+ 用例）
mvn verify

# 安装到本地仓库（模块间依赖需先 install）
mvn install -DskipTests

# 启动桌面版
cd omniforge-app && mvn javafx:run

# Headless 服务模式（健康检查 http://localhost:5119/health）
mvn -pl omniforge-app exec:java -Dexec.args="--headless"   # 或用打包产物 omniforge --headless
```

首启流程：签署 EULA → ⚙ 配置中心填入模型提供商与 API Key（内置 DeepSeek / OpenAI / Moonshot / 通义千问预设，一键填充）→ 选择模型开始对话。所有配置均可在界面完成，无需手动编辑文件。

配置与数据默认存放于 `%APPDATA%/OmniForge`（Windows）或 `~/.omniforge`（Linux/macOS）。

## 打包与安装

| 平台 | 产物 | 获取方式 |
|------|------|----------|
| Windows | 免安装镜像 / EXE 安装包 | GitHub Release 下载，或本机构建（见下） |
| Linux | deb 安装包（自动注册 systemd 服务） | GitHub Release 下载 |
| macOS | dmg 镜像 | GitHub Release 下载 |

本机构建（当前平台）：

```bash
# Windows 免安装镜像（jpackage APP_IMAGE）
mvn -P package -DskipTests -pl omniforge-app package
# 产物：omniforge-app/target/package/omniforge/omniforge.exe

# Windows EXE 安装包需本机安装 WiX 3.x：
mvn -P package -Domniforge.skip.exe=false -DskipTests -pl omniforge-app package
```

推送 `v*` 标签到 GitHub 会自动触发三平台构建并把安装包挂到 Release（Windows runner 自带 WiX，可出 EXE）。

> 说明：当前构建产物内嵌完整 JRE（约 180MB）。jlink 最小化运行时依赖 javafx-jmods 构件，镜像源补齐后可在 POM 中一键启用。

## 文档

- [架构设计](docs/ARCHITECTURE.md)（模块树 / 依赖图 / ER 图 / 依赖协议清单）
- [构建运行指南](docs/BUILD_RUN_GUIDE.md)
- [插件开发指南](docs/PLUGIN_DEV_GUIDE.md)（jar 插件 / 技能 / 市场包三格式规范）
- 设计文档（docs/）：MCP Client/Server、插件市场、分层路由、知识库分类与文件格式、上下文管理、工具执行确认、数据保留与备份、IM 各平台接入、UI 设计等

## 测试

```bash
mvn verify   # JUnit 5 + Mockito + AssertJ，430+ 用例
```

## 商业版

社区版之外提供付费授权与企业版：

- **专业版（Pro）**：在社区版基础上解锁单场辩论最多 10 个模型（社区版 3 个）、白标自定义等能力。授权码 RSA 签名绑定本机硬件指纹，支持**永久买断**与**年度订阅**（到期自动回社区版，数据无损，续订粘贴新码即可）
- **企业版**：闭源商业版，含企业服务端（多租户、团队协作、模型限额、审计管理后台、扫描件 OCR 增强），桌面客户端以 `--mode=enterprise` 连接

**获取授权 / 续订 / 企业版咨询：corbin_guo@qq.com**（把应用配置中心「专业版授权」页显示的本机指纹发到该邮箱，即可获取授权码）

## 许可

[Apache License 2.0](LICENSE)
