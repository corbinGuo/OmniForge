package com.omniforge.ui.tray;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统托盘支持（AWT TrayIcon + JavaFX 自绘菜单，零新依赖；Windows/Linux）。
 *
 * <p><b>为什么不用 AWT PopupMenu</b>：AWT 原生菜单在中文 Windows 上文字渲染
 * 为方块是 JDK 长期未修的固有限制（setFont 无效，真机实测）。本实现只保留
 * TrayIcon 承载图标与鼠标事件，右键弹出无边框 JavaFX 菜单窗口——文字渲染走
 * JavaFX（中文正常），且复用应用主题（明暗随动）。</p>
 *
 * <p>托盘必须注册在 AWT 事件分发线程（EDT）上：否则 Windows 下菜单/双击事件
 * 不派发（真机实测）。窗口隐藏后需 {@code Platform.setImplicitExit(false)}
 * 保持 FX 平台运行。</p>
 *
 * <p>Linux 部分桌面环境（无 AppIndicator 的 Wayland 会话等）不支持托盘时
 * {@link #install(Stage)} 返回 false，调用方回退为"关闭窗口即退出"。</p>
 */
public final class SystemTraySupport {

    private static final Logger log = LoggerFactory.getLogger(SystemTraySupport.class);

    private static TrayIcon trayIcon;
    /** 首次隐藏时弹一次通知，之后不再打扰 */
    private static final AtomicBoolean hideNotified = new AtomicBoolean(false);
    /** 自绘菜单窗口（懒创建，随主题自动刷新） */
    private static volatile Stage menuStage;

    private SystemTraySupport() {
    }

    /**
     * 安装托盘图标。
     *
     * @param stage 主窗口（显示/退出动作作用于它）
     * @return 是否安装成功（false = 平台不支持托盘，调用方应回退关闭即退出）
     */
    public static synchronized boolean install(Stage stage) {
        if (!SystemTray.isSupported()) {
            log.info("系统托盘不受支持（当前桌面环境），关闭窗口将直接退出");
            return false;
        }
        try {
            // 窗口隐藏到托盘后 FX 平台必须保持运行（否则 Platform.runLater 失效）
            Platform.setImplicitExit(false);

            trayIcon = new TrayIcon(createTrayImage(), "OmniForge");
            trayIcon.setImageAutoSize(true);
            // 左键单击/双击显示主窗口（Windows 两者都触发 action）
            trayIcon.addActionListener(event -> showWindow(stage));
            // 右键弹出自绘 JavaFX 菜单（AWT 原生菜单中文渲染为方块，弃用）
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent event) {
                    if (event.isPopupTrigger() && event.getButton() == MouseEvent.BUTTON3) {
                        showMenu(stage);
                    }
                }
            });

            // 关键：托盘注册放到 AWT EDT 上执行（FX 线程初始化会导致事件不派发）
            if (java.awt.EventQueue.isDispatchThread()) {
                SystemTray.getSystemTray().add(trayIcon);
            } else {
                java.awt.EventQueue.invokeAndWait(() -> {
                    try {
                        SystemTray.getSystemTray().add(trayIcon);
                    } catch (java.awt.AWTException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            log.info("系统托盘已安装（EDT，JavaFX 自绘菜单）");
            return true;
        } catch (Exception e) {
            log.warn("系统托盘安装失败（{}），关闭窗口将直接退出", e.getMessage());
            return false;
        }
    }

    /** 窗口关闭 → 隐藏到托盘（首次弹通知）；仅在托盘安装成功后调用 */
    public static void hideToTray(Stage stage) {
        stage.hide();
        if (hideNotified.compareAndSet(false, true) && trayIcon != null) {
            trayIcon.displayMessage("OmniForge 仍在运行",
                    "已最小化到系统托盘，双击图标恢复窗口，右键打开菜单。",
                    TrayIcon.MessageType.INFO);
        }
    }

    private static void showWindow(Stage stage) {
        Platform.runLater(() -> {
            stage.show();
            stage.setIconified(false);
            stage.toFront();
        });
    }

    /** 弹出边缘留白（防止菜单紧贴/越过屏幕边缘） */
    private static final double MENU_MARGIN = 6;

    /**
     * 在鼠标位置弹出 JavaFX 自绘菜单（靠近托盘图标），失焦自动隐藏。
     * 定位会钳制在光标所在屏幕的工作区内：默认在光标下方展开，
     * 下方空间不足（右下角托盘）自动改到上方，避免菜单跑到屏幕外文字被裁。
     */
    private static void showMenu(Stage appStage) {
        Platform.runLater(() -> {
            Stage menu = menuStage;
            if (menu == null) {
                menu = buildMenu(appStage);
                menuStage = menu;
            }
            // 预布局取菜单实际尺寸（stage 未 show 前 getWidth 为 0，需按内容 pref 计算）
            Scene scene = menu.getScene();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            double width = Math.max(120, scene.getRoot().prefWidth(-1));
            double height = Math.max(40, scene.getRoot().prefHeight(-1));

            Point cursor = MouseInfo.getPointerInfo().getLocation();
            Rectangle2D bounds = screenAt(cursor).getVisualBounds();

            // 默认光标正下方；下方空间不足 → 改在光标上方弹出
            double x = cursor.getX();
            double y = (bounds.getMaxY() - cursor.getY() >= height + MENU_MARGIN)
                    ? cursor.getY()
                    : cursor.getY() - height - MENU_MARGIN;
            x = clamp(x, bounds.getMinX() + MENU_MARGIN, bounds.getMaxX() - width - MENU_MARGIN);
            y = clamp(y, bounds.getMinY() + MENU_MARGIN, bounds.getMaxY() - height - MENU_MARGIN);

            menu.setX(x);
            menu.setY(y);
            menu.show();
            menu.toFront();
        });
    }

    /** 光标位置所在屏幕（多显示器场景取正确屏，避免用主屏工作区误算） */
    private static Screen screenAt(Point cursor) {
        for (Screen screen : Screen.getScreens()) {
            if (screen.getBounds().contains(cursor.getX(), cursor.getY())) {
                return screen;
            }
        }
        return Screen.getPrimary();
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static Stage buildMenu(Stage appStage) {
        Button showItem = new Button("显示主窗口");
        showItem.setMaxWidth(Double.MAX_VALUE);
        showItem.setOnAction(event -> {
            hideMenu();
            showWindow(appStage);
        });
        Button exitItem = new Button("退出");
        exitItem.setMaxWidth(Double.MAX_VALUE);
        exitItem.setOnAction(event -> {
            hideMenu();
            removeQuietly();
            Platform.exit();
        });
        VBox box = new VBox(4, showItem, exitItem);
        box.setPadding(new Insets(6));
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("tray-menu");

        Scene scene = new Scene(box);
        // 主题随动：明暗切换后菜单样式与主窗口一致
        com.omniforge.ui.theme.ThemeManager.attach(scene);

        Stage menu = new Stage(StageStyle.TRANSPARENT);
        menu.setScene(scene);
        menu.setAlwaysOnTop(true);
        // 失焦自动收起（点击菜单项、点击他处均触发）
        menu.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                menu.hide();
            }
        });
        return menu;
    }

    private static void hideMenu() {
        if (menuStage != null) {
            menuStage.hide();
        }
    }

    private static void removeQuietly() {
        try {
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
        } catch (Exception e) {
            log.debug("移除托盘图标失败：{}", e.getMessage());
        }
    }

    /** 运行时生成托盘图标：品牌蓝圆角方块 + 白色菱形（避免引入二进制资源） */
    private static BufferedImage createTrayImage() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x25, 0x63, 0xEB)); // 品牌色 #2563EB
            g.fillRoundRect(0, 0, 32, 32, 8, 8);
            // 菱形 ◆：四个顶点（16,4）(28,16) (16,28) (4,16)
            Polygon diamond = new Polygon(
                    new int[]{16, 28, 16, 4},
                    new int[]{4, 16, 28, 16},
                    4);
            g.setColor(Color.WHITE);
            g.fillPolygon(diamond);
        } finally {
            g.dispose();
        }
        return image;
    }
}
