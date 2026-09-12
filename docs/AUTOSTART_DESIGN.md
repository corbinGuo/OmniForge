# 开机自启设计（AUTOSTART_DESIGN）——B2

> 状态：**已确认**（2026-09-12，Q1-Q4 全部采纳推荐项：Q1-A HKCU Run / Q2-A XDG autostart / Q3-A 托盘菜单唯一入口实时刷新 / Q4-A 安装版限定+路径自愈）
> 最后更新：2026-09-12
> **基线：开源仓库 `c410415` / 企业仓库 `ff7b6c8`**（工作区开发窗口未提交改动不触及本设计依赖路径，已核对）
> 分工：本设计由设计窗口产出，确认后交开发窗口实施（设计窗口不编码）

## 一、背景与目标

- SYSTEM_DESIGN §一 #60（商业重构对话记录）：「桌面开机自启 + 托盘驻留」——托盘驻留已交付（隐藏到托盘 + JavaFX 自绘菜单），**开机自启**为剩余缺口。
- 目标：托盘菜单加「开机自启」开关；用户登录后应用随桌面会话自启（配合既有托盘驻留，形成"常驻后台"形态）。
- 范围：仅 GUI 模式（托盘只在 GUI 装配）；Headless 服务器场景开机自启属 systemd/服务管理，不在本设计（scripts/omniforge.service 已有 systemd 模板覆盖）。

## 二、现状（代码事实，基线 c410415）

| 事实 | 位置 |
|---|---|
| 托盘菜单 = 缓存的 JavaFX 无边框 Stage（`menuStage` 懒创建后复用），项：显示主窗口/退出——**新增项的状态刷新必须挂在每次弹出时**（build 只走一次） | omniforge-ui/…/tray/SystemTraySupport.java:52、132-162、178-211 |
| 安装包尚未产出（U5 WiX 待装）→ 本批交付后，**开发模式只能验「禁用+提示」态**，开/关闭环验收依赖 EXE/MSI（与 U5 联动） | TASKS.md U5 |
| jpackage 启动器运行时注入系统属性 `jpackage.app-path`（指向启动 exe）；`mvn javafx:run` 无此属性——**以此判定"安装版"**，避免把开发用 java 命令行写进自启项 | jpackage 标准行为 |
| Linux 桌面无托盘时 install 返回 false 回退关闭即退出（此环境自启项同样无意义，随 supported 门控） | SystemTraySupport.java:63-66 |

## 三、方案与决策点

### 3.1 Windows 机制（Q1）

**HKCU `Software\Microsoft\Windows\CurrentVersion\Run`** 注册表项，值名 `OmniForge`，值数据 = 带引号的安装版 exe 路径。经 `reg add/query/delete`（ProcessBuilder，5s 超时，错误仅告警）——零新依赖、用户级无需管理员、登出/卸载语义清晰。备选：启动文件夹 .lnk（Java 无原生快捷方式能力）、任务计划程序（过重）。

### 3.2 Linux 机制（Q2）

**XDG autostart**：`~/.config/autostart/omniforge.desktop`（`Exec="路径" omniforge`，Type=Application）。禁用 = 删文件。启用条件 = 文件存在且 Exec 指向当前程序。
> 对 SYSTEM_DESIGN B2 原提法「systemd user unit」的修正：systemd user unit 在**无桌面会话时也会拉起**进程，托盘 GUI 起来没有会话无处显示，行为错误；XDG autostart 才是"登录后随桌面会话启动"的标准机制（GNOME/KDE/XFCE 通用）。

### 3.3 入口与状态源（Q3）

- 唯一入口 = **托盘菜单「开机自启：开/关」**（点切）。注册表值 / desktop 文件**即状态源**：每次菜单弹出时实时查询并刷新该项文案与状态（菜单是缓存 Stage，不能只在构建时定一次）——无双写漂移。
- 不进配置中心（单机 GUI 的托盘项，与"显示主窗口/退出"同级）；若后续要进 GUI 再议。

### 3.4 环境判定与自愈（Q4）

- **supported** = `System.getProperty("jpackage.app-path") != null` 且平台为 Windows/Linux 且托盘已安装。不满足（开发模式 `mvn javafx:run`）→ 菜单项**禁用** + 提示「需安装版（EXE/MSI/deb）」——把开发用 java 命令行写进自启项是错误行为，必须拦。
- **自愈**：安装版每次启动时，若已启用但记录路径 ≠ 当前 exe 路径 → 静默改写（覆盖升级/换目录后自启失效），log info 一条。

### 3.5 决策点（编号回复，A/B/C）

- **Q1** Windows 机制：**A** HKCU Run 项（reg 命令，零依赖，推荐）/ B 启动文件夹快捷方式（需 JNA/额外库建 .lnk）/ C 任务计划程序（过重，权限噪音）
- **Q2** Linux 机制：**A** XDG autostart .desktop（桌面会话标准，推荐，含对总纲原提法的修正理由如上）/ B systemd user unit（无桌面会话也拉起，托盘无处显示）/ C 本批不做 Linux
- **Q3** 入口与状态源：**A** 托盘菜单唯一入口，注册表/desktop 文件即状态源、每次弹出时实时刷新（推荐）/ B 另在配置中心加开关（双状态源需同步，维护成本高）
- **Q4** 环境判定与自愈：**A** jpackage.app-path 判定安装版，开发模式禁用+提示；启动时路径变化静默自愈（推荐）/ B 开发模式也允许写入（错误做法）/ C 不做自愈（升级换路径后自启静默失效）

## 四、改动清单（纯 ui 模块；企业仓库零改动）

- 新增 `com.omniforge.ui.tray.AutoStartSupport`：
  - `supported()`（jpackage.app-path + OS + 托盘）、`isEnabled()`（reg query 解析 / desktop 文件存在且 Exec 匹配）、`setEnabled(boolean)`、`healIfNeeded()`（Q4-A）；
  - reg 命令参数串与 desktop 文件内容由**纯函数构建**（`buildRegAddArgs(path)` / `buildDesktopContent(path)`，Windows 值数据内层引号转义、路径含空格处理）；
  - 全部外部命令经 ProcessBuilder + 5s 超时 + 失败告警不抛出（托盘菜单不能被自启故障拖垮）。
- `SystemTraySupport`：菜单加「开机自启：开/关」项（unsupported 时禁用+提示）；`showMenu` 每次弹出时刷新该项状态（Q3-A）。
- `OmniForgeApplication`（GUI 启动后）：`AutoStartSupport.healIfNeeded()`。
- 测试：`AutoStartSupportTest` 纯函数用例（reg 参数串/引号转义/desktop 内容/unsupported 判定/Exec 匹配解析）≈5；开/关外部命令真跑不进单测（避免测试写用户注册表）。
- 文档：REQUIREMENTS / TASKS 同步（#60 开机自启 ✅、B2 记录）。

## 五、真机验收清单（交付后执行）

1. **开发模式（现可验）**：托盘菜单出现「开机自启」项，禁用态 + 悬停提示「需安装版」；明暗主题下样式正常。
2. **安装版（依赖 U5 WiX/EXE 产出后）**：点开 → reg query 可见 `OmniForge = "…omniforge.exe"` → 注销重登（或重启）应用随桌面自启且进托盘；点关 → 值消失，重启不再自启。
3. 路径自愈：启用后把安装目录改名/重装到新路径 → 启动一次 → Run 项指向新路径。
4. Linux（如有环境）：desktop 文件生成/删除正确，GNOME「优化→开机自启」中可见。

确认后：设计窗口更新本文状态为「已确认」→ 交付开发窗口按 §三/§四 实施（提交建议：feat(ui): launch-at-startup toggle in tray menu (B2)）。
