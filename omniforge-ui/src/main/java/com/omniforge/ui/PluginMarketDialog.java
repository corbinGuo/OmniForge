package com.omniforge.ui;

import com.omniforge.ui.plugin.PluginBridge;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.File;
import java.util.Objects;

/**
 * 插件市场面板（P1-3）：本地目录市场 + 三格式全纳管（jar / MCP / Agent Skills）。
 *
 * <p>两栏页签：<b>市场</b>（可用插件包：安装/升级/回滚）与 <b>已安装</b>
 * （技能启停、MCP 启停、jar 插件卸载）。动作在后台虚拟线程执行，经
 * {@link PluginBridge}（app 装配层实现）落到文件/mcp.yml/skills 目录，
 * 安装即由既有插件热加载/MCP 重建生效。</p>
 */
final class PluginMarketDialog {

    private final PluginBridge bridge;
    private final Label status = new Label();
    private final VBox marketBox = new VBox(8);
    private final VBox installedBox = new VBox(8);
    private final Label marketDirLabel = new Label();
    private Stage dialogStage;
    private Button marketTab;
    private Button installedTab;
    private ScrollPane contentScroll;
    private boolean busy;

    PluginMarketDialog(PluginBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    void show(Window owner) {
        status.getStyleClass().add("status");

        Button backButton = new Button("← 返回");
        backButton.setOnAction(event -> {
            if (dialogStage != null) {
                dialogStage.close();
            }
        });
        Label title = new Label("🧩 插件");
        title.getStyleClass().add("empty-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button importButton = new Button("导入插件包");
        importButton.setOnAction(event -> chooseImport());
        Button openDirButton = new Button("打开市场目录");
        openDirButton.setOnAction(event -> openMarketDir());
        HBox titleRow = new HBox(8, backButton, title, spacer, importButton, openDirButton);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        marketTab = new Button("市场");
        installedTab = new Button("已安装");
        marketTab.getStyleClass().add("primary");
        marketTab.setOnAction(event -> showPane(true));
        installedTab.setOnAction(event -> showPane(false));
        HBox tabs = new HBox(8, marketTab, installedTab);
        tabs.setAlignment(Pos.CENTER_LEFT);
        marketDirLabel.getStyleClass().add("status");
        marketDirLabel.setWrapText(true);
        marketDirLabel.setMaxWidth(Double.MAX_VALUE);

        contentScroll = new ScrollPane();
        contentScroll.setFitToWidth(true);
        contentScroll.getStyleClass().add("chat-scroll");

        BorderPane root = new BorderPane();
        root.setTop(new VBox(4, titleRow, tabs, marketDirLabel, status));
        root.setCenter(contentScroll);

        dialogStage = new Stage();
        dialogStage.setTitle("OmniForge"); // 标题去重：页内已有「🧩 插件」
        dialogStage.setScene(new Scene(root, 860, 620));
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(owner);
        com.omniforge.ui.theme.ThemeManager.attach(dialogStage.getScene());
        showPane(true);
        loadOverview();
        dialogStage.show();
    }

    /** 双栏切换：互斥托管 marketBox / installedBox */
    private void showPane(boolean market) {
        marketTab.getStyleClass().removeAll("primary");
        installedTab.getStyleClass().removeAll("primary");
        (market ? marketTab : installedTab).getStyleClass().add("primary");
        contentScroll.setContent(market ? marketBox : installedBox);
    }

    // ---------- 数据加载 ----------

    private void loadOverview() {
        if (busy) {
            return;
        }
        busy = true;
        Thread.ofVirtual().name("omniforge-plugin-overview", 0).start(() -> {
            try {
                PluginBridge.Overview overview = bridge.overview();
                Platform.runLater(() -> {
                    renderMarket(overview);
                    renderInstalled(overview);
                    marketDirLabel.setText("市场目录（把插件包拷入此处即出现）：" + overview.marketDir());
                    busy = false;
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    status.setText("市场读取失败：" + e.getMessage());
                    status.getStyleClass().removeAll("status-success", "status-error");
                    status.getStyleClass().add("status-error");
                    busy = false;
                });
            }
        });
    }

    private void renderMarket(PluginBridge.Overview overview) {
        if (overview.available().isEmpty()) {
            Button importHere = new Button("导入插件包");
            importHere.getStyleClass().add("primary");
            importHere.setOnAction(event -> chooseImport());
            VBox empty = new VBox(8,
                    new Label("市场暂无插件包"),
                    new Label("把打包好的插件包（目录或 .zip）拷入市场目录，或点击「导入插件包」。"),
                    importHere);
            empty.setAlignment(Pos.CENTER);
            empty.setPadding(new Insets(40));
            marketBox.getChildren().setAll(empty);
            return;
        }
        marketBox.getChildren().setAll(overview.available().stream().map(this::marketRow).toList());
    }

    private void renderInstalled(PluginBridge.Overview overview) {
        VBox content = new VBox(12);
        content.getChildren().add(sectionHeader("技能 Skills · " + overview.skills().size()));
        if (overview.skills().isEmpty()) {
            content.getChildren().add(sectionHint("安装技能包后默认启用，指令并入 Agent 系统提示（仅单模型 Agent 路径）"));
        } else {
            overview.skills().forEach(skill -> content.getChildren().add(skillRow(skill)));
        }
        content.getChildren().add(sectionHeader("MCP 服务器 · " + overview.mcpServers().size()));
        if (overview.mcpServers().isEmpty()) {
            content.getChildren().add(sectionHint("安装 mcp 格式插件包即添加服务器（另可在配置中心「MCP 服务器」模块管理）"));
        } else {
            overview.mcpServers().forEach(server -> content.getChildren().add(mcpRow(server)));
        }
        content.getChildren().add(sectionHeader("jar 插件 · " + overview.jars().size()));
        if (overview.jars().isEmpty()) {
            content.getChildren().add(sectionHint("把插件 jar 放入插件目录即自动加载（增/删热生效）"));
        } else {
            overview.jars().forEach(jar -> content.getChildren().add(jarRow(jar)));
        }
        installedBox.getChildren().setAll(content);
    }

    // ---------- 行渲染 ----------

    private BorderPane marketRow(PluginBridge.AvailablePlugin pkg) {
        Label name = new Label(pkg.name().isBlank() ? pkg.id() : pkg.name() + "（" + pkg.id() + "）");
        Label version = new Label("v" + pkg.version() + " · " + formatBadge(pkg.format()));
        version.getStyleClass().add("status");
        VBox text = new VBox(2, name, version);
        if (!pkg.description().isBlank()) {
            Label desc = new Label(pkg.description());
            desc.getStyleClass().add("status");
            desc.setWrapText(true);
            text.getChildren().add(desc);
        }
        Label meta = new Label("作者：" + (pkg.author().isBlank() ? "—" : pkg.author())
                + " · 许可：" + (pkg.license().isBlank() ? "—" : pkg.license()));
        meta.getStyleClass().add("status");
        text.getChildren().add(meta);
        HBox.setHgrow(text, Priority.ALWAYS);

        boolean installed = PluginBridge.STATE_INSTALLED.equals(pkg.state());
        Button primary = new Button(installed ? "已安装"
                : PluginBridge.STATE_UPGRADABLE.equals(pkg.state())
                        ? "升级到 v" + pkg.version() : "安装");
        if (!installed) {
            primary.getStyleClass().add("primary");
        } else {
            primary.setDisable(true);
        }
        primary.setOnAction(event -> confirmAndInstall(pkg));
        Button rollback = new Button("回滚");
        rollback.setDisable(!installed);
        rollback.setOnAction(event ->
                doAction("已回滚到上一版本：" + pkg.id(), () -> bridge.rollback(pkg.id())));

        return card(text, primary, rollback);
    }

    private BorderPane skillRow(PluginBridge.InstalledSkill skill) {
        Label name = new Label("📖 " + skill.name());
        VBox text = new VBox(2, name);
        if (!skill.description().isBlank()) {
            Label desc = new Label(skill.description());
            desc.getStyleClass().add("status");
            desc.setWrapText(true);
            text.getChildren().add(desc);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        ToggleSwitch toggle = new ToggleSwitch("技能");
        toggle.setSelected(skill.enabled());
        toggle.selectedProperty().addListener((obs, old, on) -> {
            if (old != null && old != on) {
                doAction(on ? "已启用技能：" + skill.name() : "已停用技能：" + skill.name(),
                        () -> bridge.setEnabled(PluginBridge.FORMAT_SKILL, skill.name(), on));
            }
        });
        Button uninstall = new Button("卸载");
        uninstall.getStyleClass().add("danger");
        uninstall.setOnAction(event ->
                doAction("已卸载技能：" + skill.name(), () -> bridge.uninstall(skill.name())));
        return row(text, toggle, uninstall);
    }

    private BorderPane mcpRow(PluginBridge.InstalledMcp server) {
        Label name = new Label("🔌 " + server.name());
        Label meta = new Label("传输：" + server.transport());
        meta.getStyleClass().add("status");
        VBox text = new VBox(2, name, meta);
        HBox.setHgrow(text, Priority.ALWAYS);
        ToggleSwitch toggle = new ToggleSwitch("MCP");
        toggle.setSelected(server.enabled());
        toggle.selectedProperty().addListener((obs, old, on) -> {
            if (old != null && old != on) {
                doAction(on ? "已启用 MCP：" + server.name() : "已停用 MCP：" + server.name(),
                        () -> bridge.setEnabled(PluginBridge.FORMAT_MCP, server.name(), on));
            }
        });
        Button uninstall = new Button("卸载");
        uninstall.getStyleClass().add("danger");
        uninstall.setOnAction(event ->
                doAction("已移除 MCP 服务器：" + server.name(), () -> bridge.uninstall(server.name())));
        return row(text, toggle, uninstall);
    }

    private BorderPane jarRow(PluginBridge.InstalledJar jar) {
        Label name = new Label("📦 " + jar.fileName());
        Label meta = new Label("插件：" + String.join("、", jar.pluginNames())
                + " · 工具：" + String.join("、", jar.tools()));
        meta.getStyleClass().add("status");
        meta.setWrapText(true);
        VBox text = new VBox(2, name, meta);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button uninstall = new Button("卸载");
        uninstall.getStyleClass().add("danger");
        uninstall.setOnAction(event ->
                doAction("已卸载插件：" + jar.fileName(), () -> bridge.uninstall(jar.id())));
        return row(text, uninstall);
    }

    /**
     * 卡片行：文本区置中占满剩余宽度（可换行），动作按钮固定于右端且不被压缩
     * （BorderPane 布局下右区取按钮 pref 宽，避免按钮文字被压成省略号）。
     */
    private static BorderPane row(VBox text, javafx.scene.Node... actions) {
        return card(text, actions);
    }

    private static BorderPane card(VBox text, javafx.scene.Node... actions) {
        HBox actionBox = new HBox(8, actions);
        actionBox.setAlignment(Pos.CENTER_RIGHT);
        BorderPane card = new BorderPane();
        card.getStyleClass().add("config-card");
        card.setPadding(new Insets(8, 10, 8, 10));
        card.setCenter(text);
        card.setRight(actionBox);
        return card;
    }

    // ---------- 安装确认 ----------

    private void confirmAndInstall(PluginBridge.AvailablePlugin pkg) {
        if (PluginBridge.FORMAT_SKILL.equals(pkg.format())) {
            doAction("已安装技能：" + pkg.id() + "（默认启用）", () -> bridge.install(pkg.id()));
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "安装「" + pkg.name() + "」v" + pkg.version() + "？"
                        + (pkg.license().isBlank() ? "" : "\n许可：" + pkg.license())
                        + "\n" + formatSecurityHint(pkg.format()),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("安装插件");
        confirm.initOwner(dialogStage);
        com.omniforge.ui.theme.ThemeManager.attachDialog(confirm);
        confirm.showAndWait().ifPresent(choice -> {
            if (choice == ButtonType.OK) {
                doAction("已安装：" + pkg.id() + "（v" + pkg.version() + "）",
                        () -> bridge.install(pkg.id()));
            }
        });
    }

    private static String formatSecurityHint(String format) {
        return switch (format) {
            case PluginBridge.FORMAT_JAR ->
                    "jar 插件代码在应用内执行（可访问本地文件与已授权沙箱），仅安装可信来源。";
            case PluginBridge.FORMAT_MCP ->
                    "MCP 服务器工具由外部进程/服务执行，不受本地沙箱约束，仅挂载可信服务器。";
            default -> "";
        };
    }

    // ---------- 动作执行（后台虚拟线程） ----------

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private void doAction(String successMessage, ThrowingAction action) {
        Thread.ofVirtual().name("omniforge-plugin-action", 0).start(() -> {
            try {
                action.run();
                Platform.runLater(() -> {
                    Toast.show(dialogStage, successMessage, true);
                    loadOverview();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Toast.show(dialogStage, e.getMessage(), false);
                    status.setText(e.getMessage());
                    status.getStyleClass().removeAll("status-success", "status-error");
                    status.getStyleClass().add("status-error");
                });
            }
        });
    }

    // ---------- 导入 / 打开目录 ----------

    private void chooseImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入插件包");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("插件包", "*.zip"),
                new FileChooser.ExtensionFilter("全部", "*.*"));
        File file = chooser.showOpenDialog(dialogStage);
        if (file == null) {
            return;
        }
        doAction("已导入插件包：" + file.getName(), () -> bridge.importPackage(file.toPath()));
    }

    private void openMarketDir() {
        try {
            String dir = bridge.overview().marketDir();
            File target = dir.isBlank() ? new File(System.getProperty("user.home")) : new File(dir);
            Desktop.getDesktop().open(target);
        } catch (Exception e) {
            Toast.show(dialogStage, "无法打开目录：" + e.getMessage(), false);
        }
    }

    // ---------- 辅助 ----------

    private Label sectionHeader(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("header");
        return label;
    }

    private static Label sectionHint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("status");
        label.setWrapText(true);
        return label;
    }

    private static String formatBadge(String format) {
        return switch (format) {
            case PluginBridge.FORMAT_JAR -> "jar 插件";
            case PluginBridge.FORMAT_MCP -> "MCP 服务器";
            case PluginBridge.FORMAT_SKILL -> "技能";
            default -> format;
        };
    }
}
