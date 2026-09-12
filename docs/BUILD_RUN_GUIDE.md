# 启动与打包操作流程（v0.1.0，2026-09-02）

> 三种终端均可：PowerShell（推荐，Windows 默认）、CMD、Git Bash。按你打开的终端选对应命令。

## 一、环境前置

| 项 | 要求 |
|---|---|
| JDK | 21 LTS（推荐 Eclipse Temurin） |
| Maven | 3.9+ |
| JAVA_HOME | 构建前显式设置（避免 PATH 上的其他 JDK 干扰），指向你的 JDK 21 安装目录 |

所有命令先设置 JAVA_HOME（三终端任选其一）：

```powershell
# PowerShell
$env:JAVA_HOME = '<JDK21安装目录>'
```

```bat
:: CMD
set JAVA_HOME=<JDK21安装目录>
```

```bash
# Git Bash
export JAVA_HOME='<JDK21安装目录>'
```

> 注意：CMD/PowerShell 窗口关闭后 JAVA_HOME 失效，需重新设置；想永久生效可在「系统属性 → 环境变量」里添加。

## 二、启动程序

> 以下示例用 PowerShell；CMD 用户把 `cd D:\...` 保持原样、`$env:` 换成 `set` 即可，命令主体（mvn）完全相同。

### 2.1 首次构建 / 依赖更新后（必做一次）

```powershell
cd <仓库根目录>
$env:JAVA_HOME = '<JDK21安装目录>'
mvn install -DskipTests
```

> 模块间依赖需先 install 到本地仓库（`mvn verify` 不安装，直接 `javafx:run` 会找不到模块）。

### 2.2 启动 GUI（日常开发/使用）

```powershell
cd <仓库根目录>\omniforge-app
$env:JAVA_HOME = '<JDK21安装目录>'
mvn javafx:run
```

- 主类 `com.omniforge.app.OmniForgeLauncher`（javafx-maven-plugin 0.0.8 已配置）
- 需要 Python 解释器工具（JEP）时，构建与启动加 `-P python`（运行需本机安装 JEP native：Windows 需 MSVC 源码编译，或使用 Linux）
- **关闭窗口 = 最小化到系统托盘**；彻底退出点托盘菜单「退出」
- 停止：在启动窗口按 `Ctrl + C`（或直接关掉该终端窗口）

### 2.3 启动 Headless（无界面服务模式）

开发态：

```powershell
cd <仓库根目录>\omniforge-app
mvn javafx:run -Djavafx.commandlineArgs=--headless
```

打包产物（推荐，见三）：

```powershell
cd <仓库根目录>\omniforge-app\target\package\omniforge
.\omniforge.exe --headless
```

- 健康检查：`http://localhost:5119/health`（端口可用环境变量 `OMNIFORGE_HEADLESS_PORT` 覆盖）
- 指标：`http://localhost:5119/metrics`（Prometheus 文本）
- 日志：`<配置目录>\logs\omniforge.log`（Windows 默认 `%APPDATA%\OmniForge\logs\`）
- EULA：首次需环境变量 `OMNI_ACCEPT_EULA=1`，否则拒绝启动：

```powershell
$env:OMNI_ACCEPT_EULA = '1'
.\omniforge.exe --headless
```

### 2.4 启动企业版客户端

```powershell
cd <仓库根目录>\omniforge-app
mvn javafx:run -Djavafx.commandlineArgs=--mode=enterprise
```

需要 `enterprise.yml`（服务端地址）或首次登录时填写；企业版服务端为闭源商业版，单独部署。

## 三、打包

> 打包前务必先 `mvn install -DskipTests`（含 `-P python` 若需）。

### 3.1 免安装镜像 APP_IMAGE（Windows，无需 WiX）

```powershell
cd <仓库根目录>
$env:JAVA_HOME = '<JDK21安装目录>'
mvn -P package -pl omniforge-app package
```

产物：`omniforge-app\target\package\omniforge\`，直接运行 `omniforge.exe`（可加 `--headless`）。

### 3.2 EXE 安装包（需先安装 WiX 3.x）

1. 安装 WiX Toolset 3.x
2. 构建：

```powershell
mvn -P package -pl omniforge-app -Domniforge.skip.exe=false package
```

产物：`omniforge-app\target\package\omniforge-0.1.0.exe`

### 3.3 MSI 安装包（需 WiX 3.x）

```powershell
mvn -P package-msi -pl omniforge-app jpackage:jpackage@msi-installer
```

产物：`omniforge-app\target\package\omniforge-0.1.0.msi`

### 3.4 Linux deb（需在 Linux 环境执行）

```bash
bash scripts/build-linux-package.sh
```

> jpackage 只能为当前操作系统打包；脚本内置 systemd 服务注册（`--headless` 启动、omniforge 用户）。

## 四、常见问题

| 现象 | 处理 |
|---|---|
| `mvn javafx:run` 报 No plugin found for prefix 'javafx' | 在仓库根执行（前缀只在 app 模块 POM 声明）；正确方式见 2.2 |
| 启动后仍是旧代码/旧样式 | 有残留 java 进程。杀法：`powershell -Command "Get-Process java \| Where-Object {\$_.MainWindowTitle -eq 'OmniForge'} \| Stop-Process -Force"` |
| `mvn clean` 报文件被占用 | 残留 JVM 锁了 target；杀 java 进程后重试，或 cd 回仓库根（持久 shell cwd 停在 target 会失败） |
| 启动报端口占用（5119/5120/8080 等） | 健康检查 `OMNIFORGE_HEADLESS_PORT` 覆盖；NapCat 事件端口 `omniforge.im.napcat.event-port`；QQ Webhook 端口在配置中心改 |
| 模型不可用（初始化失败） | 缺对应环境变量 API Key（如 OPENAI_API_KEY）——配置中心「提供商」里改成自己的密钥 |
| EXE 打包报 NoClassDefFound（maven-shared-utils） | 插件已知坑已内置于 POM；确认 Maven 3.9+ 后重试 |
| 打包产物运行时较大（~180MB） | 当前默认完整 JDK 运行时；javafx-jmods 镜像补齐后可切 jlink 最小运行时（POM 留了切换注释） |

## 附录：配置与数据目录

- Windows：`%APPDATA%\OmniForge\`（models.yml / tools.yml / context.yml / mcp.yml / qq-im.yml / retention.yml / ui.yml / branding.yml / keys / vectors.db / logs / plugins / market / skills / backups）
- Linux / macOS：`~/.omniforge/`（同上结构）
- 日志：`<配置目录>\logs\omniforge.log`（10MB×5 滚动 + gz 归档）；审计日志：`<配置目录>\logs\audit\`（按天 JSONL）
