package com.omniforge.ui.branding;

import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 白标管理器（C-tier 批次 4-1 + P2-1 运营中心 2026-09-05）：启动时加载 branding.yml，
 * 向 UI 提供应用名称 / Logo / 主题色；支持 {@link #reload()} 热重载（运营中心保存品牌后调用）。
 *
 * <p>主题色落地方式：生成品牌覆盖样式表（<配置目录>/branding-theme.css，
 * 内容为 .root 的 -of-brand / -of-brand-hover 令牌覆盖），由
 * {@link com.omniforge.ui.theme.ThemeManager#attach} 在基础主题之后追加——
 * 明暗主题与品牌色三方叠加，互不干扰。未配置主题色时删除陈旧覆盖文件。</p>
 */
public final class BrandingManager {

    private static final Logger log = LoggerFactory.getLogger(BrandingManager.class);

    private static final String OVERRIDE_CSS_NAME = "branding-theme.css";

    /** 应用版本（与父 POM 一致；打包后可改读 Implementation-Version） */
    public static final String APP_VERSION = "0.1.2";

    private static final BrandingSettingsStore store = new BrandingSettingsStore();

    private static volatile BrandingSettings current = BrandingSettings.defaults();
    private static volatile Path overrideCssFile;
    private static volatile boolean initialized = false;

    private BrandingManager() {
    }

    /** 启动时调用一次：加载 branding.yml 并生成主题色覆盖样式表 */
    public static synchronized void init() {
        init(configDir());
    }

    /** 指定配置目录初始化（测试注入） */
    static synchronized void init(Path configDir) {
        if (initialized) {
            return;
        }
        initialized = true;
        applyConfigDir(configDir);
    }

    /**
     * 热重载（运营中心品牌保存后调用）：重读 branding.yml、重建/删除主题色覆盖样式表，
     * 刷新应用名/Logo 缓存。不要求已 init（首次即等价 init）。
     */
    public static synchronized void reload() {
        reload(configDir());
    }

    /** 指定配置目录热重载（测试注入） */
    public static synchronized void reload(Path configDir) {
        applyConfigDir(configDir);
    }

    /** 默认配置目录（branding.yml 所在目录；Linux ~/.omniforge，Windows %APPDATA%\OmniForge） */
    public static Path configDir() {
        return defaultConfigDir();
    }

    /** 应用名称（窗口标题/导航栏/关于页面） */
    public static String appName() {
        return current.appName();
    }

    /** 主题色覆盖样式表文件（未配置主题色返回 null） */
    public static Path themeColorOverrideCssFile() {
        return overrideCssFile;
    }

    /**
     * Logo 图片（branding.yml logoPath，文件存在时返回；否则 null，UI 回退文字 Logo）。
     * 每次调用重新加载，避免图片文件替换后需重启。
     */
    public static Image logoImage() {
        String path = current.logoPath();
        if (path.isEmpty()) {
            return null;
        }
        Path file = Paths.get(path);
        if (!Files.isRegularFile(file)) {
            log.warn("白标 Logo 文件不存在：{}", path);
            return null;
        }
        try {
            Image image = new Image(file.toUri().toString());
            return image.isError() ? null : image;
        } catch (RuntimeException e) {
            log.warn("白标 Logo 加载失败：{}", e.getMessage());
            return null;
        }
    }

    /** 应用当前配置目录的白标设置：读 yml + 生成/删除主题色覆盖样式表 */
    private static void applyConfigDir(Path configDir) {
        Objects.requireNonNull(configDir, "configDir");
        current = store.load(configDir.resolve("branding.yml"));
        Path override = configDir.resolve(OVERRIDE_CSS_NAME);
        if (!current.themeColor().isEmpty()) {
            try {
                Files.writeString(override, themeColorCss(current.themeColor()));
                overrideCssFile = override;
            } catch (IOException e) {
                overrideCssFile = null;
                log.warn("品牌主题色样式表生成失败（已忽略）：{}", e.getMessage());
            }
        } else {
            try {
                Files.deleteIfExists(override);
            } catch (IOException e) {
                log.debug("删除陈旧品牌样式表失败：{}", e.getMessage());
            }
            overrideCssFile = null;
        }
        log.info("白标已加载：{}（主题色 {}，Logo {}）", current.appName(),
                current.themeColor().isEmpty() ? "默认" : current.themeColor(),
                current.logoPath().isEmpty() ? "无" : current.logoPath());
    }

    /** 主题色 → 品牌令牌覆盖样式表内容（hover 加深 10%） */
    static String themeColorCss(String color) {
        int r = Integer.parseInt(color.substring(1, 3), 16);
        int g = Integer.parseInt(color.substring(3, 5), 16);
        int b = Integer.parseInt(color.substring(5, 7), 16);
        String hover = String.format("#%02x%02x%02x",
                (int) (r * 0.9), (int) (g * 0.9), (int) (b * 0.9));
        return ".root {\n    -of-brand: " + color + ";\n    -of-brand-hover: " + hover + ";\n}\n";
    }

    /** 默认配置目录：Linux ~/.omniforge；Windows %APPDATA%\OmniForge（与其他模块一致） */
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
