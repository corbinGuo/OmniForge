package com.omniforge.ui.theme;

import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 明暗主题管理器（单例静态）：
 * <ul>
 *   <li>{@link #init()} 启动时从 ui.yml 恢复上次主题（缺失/损坏回退暗色）；</li>
 *   <li>{@link #attach(Scene)} 注册场景并应用当前主题样式表——所有窗口/对话框统一入口；</li>
 *   <li>{@link #toggle()} 切换主题、持久化，并对所有存活场景热生效（JavaFX 样式表即时重解析）。</li>
 * </ul>
 * 场景以弱引用持有，对话框关闭后自动回收，不阻碍 GC。
 */
public final class ThemeManager {

    private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);

    private static final String DARK_CSS = "app.css";
    private static final String LIGHT_CSS = "app-light.css";

    private static final Set<Scene> scenes =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final UiSettingsStore store = new UiSettingsStore();

    private static volatile String current = UiSettings.THEME_DARK;
    private static volatile boolean initialized = false;

    private ThemeManager() {
    }

    /** 启动时调用一次：从 ui.yml 恢复上次主题 */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        current = UiSettings.normalize(store.load(settingsFile()).theme());
        log.info("界面主题：{}", current);
    }

    /** 当前主题（dark/light） */
    public static String currentTheme() {
        return current;
    }

    /** 注册场景并应用当前主题（所有窗口/对话框的样式表统一入口） */
    public static void attach(Scene scene) {
        scenes.add(scene);
        apply(scene);
    }

    /**
     * 内建 Dialog（Alert / TextInputDialog 等）的 Scene 在 show() 时才创建，
     * 无法提前 attach：监听 dialogPane 的场景就绪事件，届时应用主题并纳入热切换集合
     * （暗色主题下避免弹出未挂主题的白色系统对话框——P0 修复）。
     */
    public static void attachDialog(javafx.scene.control.Dialog<?> dialog) {
        dialog.getDialogPane().sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                attach(scene);
            }
        });
    }

    /**
     * 上下文菜单（ContextMenu）在 show() 时才创建其 Popup Scene，无法预先 attach。
     * 监听 showing：把主题样式表挂到其 Popup Scene，右键菜单与主窗口同主题
     * （全局把 -fx-text-background-color 提亮后，Modena 默认浅底菜单会白字浅底看不清——需显式配色）。
     */
    public static void attach(javafx.scene.control.ContextMenu menu) {
        menu.setOnShowing(event -> {
            javafx.scene.Scene scene = menu.getScene();
            if (scene != null) {
                attach(scene);
            }
        });
    }

    /** 切换明暗主题：持久化失败不阻断切换（下次启动回退旧值） */
    public static synchronized void toggle() {
        current = UiSettings.THEME_DARK.equals(current) ? UiSettings.THEME_LIGHT : UiSettings.THEME_DARK;
        try {
            store.save(settingsFile(), new UiSettings(current));
        } catch (IOException e) {
            log.warn("主题设置保存失败（ui.yml）：{}", e.getMessage());
        }
        reapplyAll();
        log.info("界面主题已切换：{}", current);
    }

    /** 对所有存活场景重新挂样式表（白标/主题变更后调用；运营中心品牌保存即时生效） */
    public static void reapplyAll() {
        scenes.forEach(ThemeManager::apply);
    }

    private static void apply(Scene scene) {
        // 绝对路径：CSS 位于 com/omniforge/ui/，本类在 com.omniforge.ui.theme/（相对解析会落错包）
        // 关键：明亮主题必须叠加加载 app.css（结构规则+暗色令牌）再叠加 app-light.css（仅令牌覆盖）——
        // 若只加载 app-light.css，所有控件样式（气泡/按钮/卡片等）会退回 Modena 默认（P0 修复）
        java.util.List<String> stylesheets = new java.util.ArrayList<>();
        addStylesheet(stylesheets, DARK_CSS);
        if (UiSettings.THEME_LIGHT.equals(current)) {
            addStylesheet(stylesheets, LIGHT_CSS);
        }
        // 白标主题色覆盖：品牌令牌在基础主题之后追加（明暗 × 品牌三方叠加）
        java.nio.file.Path brandOverride =
                com.omniforge.ui.branding.BrandingManager.themeColorOverrideCssFile();
        if (brandOverride != null && java.nio.file.Files.exists(brandOverride)) {
            // ?v=lastModified 缓存爆破：品牌重载后同 URL 也能让 JavaFX 重新解析（P2-1 即时生效）
            String url = brandOverride.toUri().toString();
            try {
                url += "?v=" + java.nio.file.Files.getLastModifiedTime(brandOverride).toMillis();
            } catch (IOException e) {
                log.debug("品牌样式表时间戳读取失败（按无缓存爆破处理）：{}", e.getMessage());
            }
            stylesheets.add(url);
        }
        scene.getStylesheets().setAll(stylesheets);
    }

    private static void addStylesheet(java.util.List<String> stylesheets, String cssName) {
        URL css = ThemeManager.class.getResource("/com/omniforge/ui/" + cssName);
        if (css == null) {
            log.warn("主题样式表缺失：{}", cssName);
            return;
        }
        stylesheets.add(css.toExternalForm());
    }

    private static Path settingsFile() {
        return defaultConfigDir().resolve("ui.yml");
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
