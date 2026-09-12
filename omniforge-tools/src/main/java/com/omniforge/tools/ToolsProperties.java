package com.omniforge.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 工具生态配置（前缀 {@code omniforge.tools}）。
 *
 * <p>默认值优先读取环境变量（TAVILY_API_KEY / SEARXNG_BASE_URL），
 * 使无 Spring 环境（ServiceLoader 场景）下内置工具也可用。</p>
 */
@ConfigurationProperties(prefix = "omniforge.tools")
public class ToolsProperties {

    /** Tavily API Key（默认读环境变量 TAVILY_API_KEY） */
    private String tavilyApiKey = System.getenv("TAVILY_API_KEY");

    /** SearXNG 服务地址（默认读环境变量 SEARXNG_BASE_URL） */
    private String searxngBaseUrl = System.getenv("SEARXNG_BASE_URL");

    /** 工作区根目录（file_read_write 沙箱，默认工作区，相对路径的解析基目录） */
    private Path workspaceRoot = defaultConfigDir().resolve("workspace");

    /** 工具设置文件（tools.yml；播种 holder 与 UI 配置中心统一读写的落点） */
    private Path configFile = defaultConfigDir().resolve("tools.yml");

    /** shell_executor 开关（默认禁用，需求 4.5 安全约束） */
    private boolean shellEnabled = false;

    /** shell 命令超时（秒） */
    private long shellTimeoutSeconds = 30;

    /** shell 黑名单前缀（在默认黑名单基础上扩展） */
    private List<String> shellBlacklist = new ArrayList<>();

    /** python_interpreter 开关 */
    private boolean pythonEnabled = true;

    /** Python 执行超时（秒） */
    private long pythonTimeoutSeconds = 60;

    /** Python 代码黑名单正则 */
    private List<String> pythonBlacklist = List.of(
            "\\bos\\.system\\b",
            "\\bos\\.popen\\b",
            "\\bsubprocess\\b",
            "\\bshutil\\.rmtree\\b",
            "\\bshutil\\.copytree\\b",
            "\\b__import__\\b",
            "\\beval\\s*\\(",
            "\\bexec\\s*\\(",
            "\\bcompile\\s*\\(");

    /** 搜索 HTTP 超时（秒） */
    private long searchTimeoutSeconds = 30;

    public String getTavilyApiKey() {
        return tavilyApiKey;
    }

    public void setTavilyApiKey(String tavilyApiKey) {
        this.tavilyApiKey = tavilyApiKey;
    }

    public String getSearxngBaseUrl() {
        return searxngBaseUrl;
    }

    public void setSearxngBaseUrl(String searxngBaseUrl) {
        this.searxngBaseUrl = searxngBaseUrl;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public Path getConfigFile() {
        return configFile;
    }

    public void setConfigFile(Path configFile) {
        this.configFile = configFile;
    }

    public boolean isShellEnabled() {
        return shellEnabled;
    }

    public void setShellEnabled(boolean shellEnabled) {
        this.shellEnabled = shellEnabled;
    }

    public long getShellTimeoutSeconds() {
        return shellTimeoutSeconds;
    }

    public void setShellTimeoutSeconds(long shellTimeoutSeconds) {
        this.shellTimeoutSeconds = shellTimeoutSeconds;
    }

    public List<String> getShellBlacklist() {
        return shellBlacklist;
    }

    public void setShellBlacklist(List<String> shellBlacklist) {
        this.shellBlacklist = shellBlacklist != null ? shellBlacklist : new ArrayList<>();
    }

    public boolean isPythonEnabled() {
        return pythonEnabled;
    }

    public void setPythonEnabled(boolean pythonEnabled) {
        this.pythonEnabled = pythonEnabled;
    }

    public long getPythonTimeoutSeconds() {
        return pythonTimeoutSeconds;
    }

    public void setPythonTimeoutSeconds(long pythonTimeoutSeconds) {
        this.pythonTimeoutSeconds = pythonTimeoutSeconds;
    }

    public List<String> getPythonBlacklist() {
        return pythonBlacklist;
    }

    public void setPythonBlacklist(List<String> pythonBlacklist) {
        this.pythonBlacklist = pythonBlacklist != null ? pythonBlacklist : List.of();
    }

    public long getSearchTimeoutSeconds() {
        return searchTimeoutSeconds;
    }

    public void setSearchTimeoutSeconds(long searchTimeoutSeconds) {
        this.searchTimeoutSeconds = searchTimeoutSeconds;
    }

    /** 黑名单正则编译缓存（pythonTool 每轮执行使用） */
    public List<Pattern> pythonBlacklistPatterns() {
        return pythonBlacklist.stream().map(Pattern::compile).toList();
    }

    /** 默认配置目录：Linux ~/.omniforge；Windows %APPDATA%\OmniForge（与 core.gateway 一致） */
    private static Path defaultConfigDir() {
        String userHome = System.getProperty("user.home", ".");
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.isBlank()) ? Paths.get(appData) : Paths.get(userHome);
            return base.resolve("OmniForge");
        }
        return Paths.get(userHome, ".omniforge");
    }
}
