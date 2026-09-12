package com.omniforge.ui.tray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 开机自启支持（B2，AUTOSTART_DESIGN Q1-Q4 全 A）：
 * 仅安装版（jpackage 启动器注入 {@code jpackage.app-path}）可用。
 *
 * <ul>
 *   <li>Windows：HKCU {@code Software\Microsoft\Windows\CurrentVersion\Run} 值
 *       {@code OmniForge = "exe 路径"}（reg 命令，用户级免管理员，零新依赖）；</li>
 *   <li>Linux：XDG autostart {@code ~/.config/autostart/omniforge.desktop}
 *       （登录后随桌面会话启动；systemd user unit 会在无桌面时错误拉起 GUI，故不用）。</li>
 * </ul>
 *
 * <p>全部外部命令经 ProcessBuilder + 5s 超时，失败仅告警不抛出
 * （托盘菜单不能被自启故障拖垮）。注册表/desktop 文件即状态源（Q3-A，无双写）。</p>
 */
public final class AutoStartSupport {

    private static final Logger log = LoggerFactory.getLogger(AutoStartSupport.class);

    /** Windows HKCU Run 键 */
    static final String WIN_RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    /** 注册表值名 / 应用名 */
    static final String APP_NAME = "OmniForge";
    /** Linux XDG autostart 文件 */
    static final String LINUX_DESKTOP_FILE = ".config/autostart/omniforge.desktop";

    private static final long COMMAND_TIMEOUT_MS = 5_000;

    private AutoStartSupport() {
    }

    /** 当前安装版启动器路径（jpackage 注入；开发模式为 null） */
    public static String launcherPath() {
        return System.getProperty("jpackage.app-path");
    }

    /** 本环境是否支持开机自启：安装版 + Windows/Linux + 有托盘（Q4-A；开发模式 false → 菜单项禁用） */
    public static boolean supported() {
        if (launcherPath() == null) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean knownOs = os.contains("win") || os.contains("linux");
        return knownOs && java.awt.SystemTray.isSupported();
    }

    /** 当前是否已启用自启（状态源：注册表值存在 / desktop 文件存在） */
    public static boolean isEnabled() {
        String path = launcherPath();
        if (path == null) {
            return false;
        }
        if (isWindows()) {
            Result result = run(List.of("reg", "query", WIN_RUN_KEY, "/v", APP_NAME));
            return result.exitCode == 0 && regQueryMatches(result.output, path);
        }
        Path desktop = desktopFile();
        return Files.isRegularFile(desktop) && desktopMatches(readQuietly(desktop), path);
    }

    /** 开/关自启（Q3-A 点切；失败返回 false，菜单态以下一次查询为准） */
    public static boolean setEnabled(boolean enable) {
        String path = launcherPath();
        if (path == null) {
            return false;
        }
        try {
            if (isWindows()) {
                if (enable) {
                    return run(buildRegAddArgs(path)).exitCode == 0;
                }
                // 值不存在时 delete 返回非零也算达成目标
                run(List.of("reg", "delete", WIN_RUN_KEY, "/v", APP_NAME, "/f"));
                return true;
            }
            Path desktop = desktopFile();
            if (enable) {
                Files.createDirectories(desktop.getParent());
                Files.writeString(desktop, buildDesktopContent(path), StandardCharsets.UTF_8);
                return true;
            }
            Files.deleteIfExists(desktop);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("开机自启设置失败（enable={}）：{}", enable, e.getMessage());
            return false;
        }
    }

    /**
     * 启动自愈（Q4-A）：已启用但记录路径 ≠ 当前 exe（覆盖安装/换目录）→ 静默改写一次。
     * 在 GUI 启动完成后调用；失败仅告警。
     */
    public static void healIfNeeded() {
        try {
            String path = launcherPath();
            if (path == null || !isEnabled()) {
                return;
            }
            if (isWindows()) {
                Result result = run(List.of("reg", "query", WIN_RUN_KEY, "/v", APP_NAME));
                if (result.exitCode == 0 && !regQueryMatches(result.output, path)) {
                    log.info("开机自启路径已变化，自动改写为当前安装路径");
                    setEnabled(true);
                }
            } else if (!desktopMatches(readQuietly(desktopFile()), path)) {
                log.info("开机自启路径已变化，自动改写为当前安装路径");
                setEnabled(true);
            }
        } catch (RuntimeException e) {
            log.warn("开机自启自愈失败：{}", e.getMessage());
        }
    }

    // ---------- 纯函数（单测覆盖；Windows 值数据内层引号、路径空格等细节在此固化） ----------

    /** 构建 reg add 参数串：值数据 = 带引号的 exe 路径（内层引号按 Windows 约定转义） */
    static List<String> buildRegAddArgs(String exePath) {
        String data = "\"" + exePath.replace("\"", "\\\"") + "\"";
        return List.of("reg", "add", WIN_RUN_KEY, "/v", APP_NAME,
                "/t", "REG_SZ", "/d", data, "/f");
    }

    /** 构建 XDG autostart .desktop 文件内容（Exec 带引号防路径空格） */
    static String buildDesktopContent(String exePath) {
        return "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=" + APP_NAME + "\n"
                + "Comment=OmniForge AI 工作台（开机自启）\n"
                + "Exec=\"" + exePath + "\"\n"
                + "X-GNOME-Autostart-enabled=true\n";
    }

    /** reg query 输出是否指向当前 exe（分隔符/大小写归一化后包含判定） */
    static boolean regQueryMatches(String queryOutput, String exePath) {
        return queryOutput != null
                && normalize(queryOutput).contains(normalize(exePath));
    }

    /** desktop 文件内容是否启用当前 exe（Exec="..." 带引号精确包含判定） */
    static boolean desktopMatches(String desktopContent, String exePath) {
        return desktopContent != null
                && normalize(desktopContent).contains("exec=\"" + normalize(exePath) + "\"");
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace('/', '\\').toLowerCase();
    }

    // ---------- 内部执行 ----------

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static Path desktopFile() {
        return Path.of(System.getProperty("user.home"), LINUX_DESKTOP_FILE.split("/"));
    }

    private static String readQuietly(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 外部命令执行结果（output = 合并 stdout/stderr） */
    private record Result(int exitCode, String output) {
    }

    private static Result run(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("外部命令超时（{}s）：{}", COMMAND_TIMEOUT_MS / 1000, command.get(0));
                return new Result(-1, "");
            }
            return new Result(process.exitValue(), output);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("外部命令执行失败：{}（{}）", command.get(0), e.getMessage());
            return new Result(-1, "");
        }
    }
}
