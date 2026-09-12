package com.omniforge.ui;

import com.omniforge.core.audit.AuditEntry;
import com.omniforge.core.audit.AuditLogService;
import com.omniforge.ui.branding.BrandingManager;
import com.omniforge.ui.branding.BrandingSettings;
import com.omniforge.ui.branding.BrandingSettingsStore;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 运营中心（P2-1，2026-09-05）：双页签——审计日志可视化查询 + 白标品牌配置。
 *
 * <p>审计页：时间范围（今天/近7天/近30天/自定义）经 {@link AuditLogService#query} 在虚拟线程读盘，
 * 表格过滤（用户/模型/类型/含工具/仅异常）、每页 200 行分页、摘要卡、行详情（工具序列表 + 输入全文）、
 * 导出当前筛选结果 CSV。审计 Bean 未装配（audit.enabled=false）时显示「未启用」占位。</p>
 *
 * <p>白标页：应用名 / 主题色 / Logo 编辑 + 保存前实时预览；保存写 branding.yml →
 * {@link BrandingManager#reload()} + {@link com.omniforge.ui.theme.ThemeManager#reapplyAll()}
 * → 回调主窗口刷新标题/Logo，即时生效。</p>
 */
final class OpsCenterDialog {

    private static final Logger log = LoggerFactory.getLogger(OpsCenterDialog.class);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int PAGE_SIZE = 200;
    private static final String[] PRESETS = {"近 7 天", "今天", "近 30 天", "自定义"};
    private static final List<String> REQUEST_TYPES = List.of("agent", "chat", "debate");

    private final AuditLogService auditLogService;
    /** 品牌保存成功后的主窗口刷新回调（标题/Logo） */
    private final Runnable onBrandingChanged;
    /** Pro 授权是否激活（品牌白标整页签为 Pro 功能，CE 禁用编辑） */
    private final boolean proEnabled;
    /** CE 点击「前往激活 Pro」的回调（打开配置中心 License 授权模块） */
    private final Runnable onActivatePro;

    // —— 审计页状态 ——
    private List<AuditEntry> loaded = List.of();
    private List<AuditEntry> filtered = List.of();
    private int page = 0;
    private final ComboBox<String> presetCombo = new ComboBox<>();
    private final DatePicker fromPicker = new DatePicker();
    private final DatePicker toPicker = new DatePicker();
    private final TextField userFilter = new TextField();
    private final ComboBox<String> modelFilter = new ComboBox<>();
    private final ComboBox<String> typeFilter = new ComboBox<>();
    private final CheckBox hasToolFilter = new CheckBox("含工具");
    private final CheckBox abnormalFilter = new CheckBox("仅异常");
    private final Label statusLabel = new Label();
    private final Label pageLabel = new Label();
    private final TableView<AuditEntry> table = new TableView<>();
    private final TextArea inputArea = new TextArea();
    private final TextArea toolArea = new TextArea();

    // —— 白标页 ——
    private final TextField appNameField = new TextField();
    private final ColorPicker colorPicker = new ColorPicker();
    private final CheckBox defaultColor = new CheckBox("默认品牌蓝（不写主题色）");
    private final TextField logoPathField = new TextField();
    private final ImageView logoPreview = new ImageView();
    private final Label brandPreview = new Label();
    private final TextField brandPathField = new TextField();

    OpsCenterDialog(AuditLogService auditLogService, Runnable onBrandingChanged,
                    boolean proEnabled, Runnable onActivatePro) {
        this.auditLogService = auditLogService;
        this.onBrandingChanged = onBrandingChanged;
        this.proEnabled = proEnabled;
        this.onActivatePro = onActivatePro;
    }

    void show(Window owner) {
        Stage stage = new Stage();
        stage.setTitle("运营中心");
        TabPane tabs = new TabPane();
        Tab auditTab = new Tab("审计日志", buildAuditPane());
        Tab brandTab = new Tab("品牌白标", buildBrandPane());
        tabs.getTabs().addAll(auditTab, brandTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Scene scene = new Scene(tabs, 1060, 700);
        stage.setScene(scene);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        com.omniforge.ui.theme.ThemeManager.attach(scene);
        stage.show();

        // 打开即加载默认近 7 天（审计未启用时跳过，白标页仍可用）
        if (auditLogService != null) {
            Platform.runLater(() -> {
                presetCombo.setValue("近 7 天");
                applyPreset();
                refreshRange();
            });
        }
    }

    // ================= 审计页 =================

    private BorderPane buildAuditPane() {
        if (auditLogService == null) {
            Label disabled = new Label("审计日志未启用（omniforge.audit.enabled=false）");
            disabled.getStyleClass().add("empty-title");
            Label hint = new Label("在配置目录 application.yml 将 omniforge.audit.enabled 设为 true 并重启。");
            hint.getStyleClass().add("status");
            VBox box = new VBox(8, disabled, hint);
            box.setAlignment(Pos.CENTER);
            return new BorderPane(box);
        }

        presetCombo.getItems().addAll(PRESETS);
        presetCombo.setOnAction(e -> applyPreset());
        fromPicker.setValue(LocalDate.now().minusDays(6));
        toPicker.setValue(LocalDate.now());
        fromPicker.setDisable(true);
        toPicker.setDisable(true);

        Button queryButton = new Button("↻ 查询");
        queryButton.getStyleClass().add("primary");
        queryButton.setOnAction(e -> refreshRange());
        Button exportButton = new Button("⬇ 导出 CSV");
        exportButton.setOnAction(e -> exportCsv(stageOf(queryButton)));

        userFilter.setPromptText("用户关键词");
        userFilter.setPrefWidth(120);
        userFilter.textProperty().addListener((o, a, b) -> applyFilters());
        modelFilter.setPrefWidth(140);
        modelFilter.setOnAction(e -> applyFilters());
        typeFilter.setPrefWidth(90);
        typeFilter.getItems().add("");
        typeFilter.setValue("");
        typeFilter.setOnAction(e -> applyFilters());
        hasToolFilter.setOnAction(e -> applyFilters());
        abnormalFilter.setOnAction(e -> applyFilters());

        Label modelLbl = new Label("模型"); modelLbl.getStyleClass().add("status");
        Label typeLbl = new Label("类型"); typeLbl.getStyleClass().add("status");
        statusLabel.getStyleClass().add("status");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        pageLabel.getStyleClass().add("status");

        Button prev = new Button("‹ 上一页");
        Button next = new Button("下一页 ›");
        prev.setOnAction(e -> { if (page > 0) { page--; renderTable(); } });
        next.setOnAction(e -> { if ((page + 1) * PAGE_SIZE < filtered.size()) { page++; renderTable(); } });

        HBox rangeRow = new HBox(8, label("范围"), presetCombo,
                label("从"), fromPicker, label("至"), toPicker,
                queryButton, exportButton);
        rangeRow.setAlignment(Pos.CENTER_LEFT);

        HBox filterRow = new HBox(8, label("用户"), userFilter, modelLbl, modelFilter,
                typeLbl, typeFilter, hasToolFilter, abnormalFilter);
        filterRow.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox pager = new HBox(8, prev, next, pageLabel, spacer);
        pager.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(6, rangeRow, filterRow, statusLabel, pager);

        table.getColumns().setAll(column("时间", entry -> TIME_FMT.format(entry.timestamp()), 150),
                column("类型", AuditEntry::requestType, 60),
                column("模型", AuditEntry::modelAlias, 110),
                column("用户", AuditEntry::user, 100),
                column("会话", entry -> truncate(entry.sessionId(), 18), 90),
                column("耗时", entry -> entry.durationMs() + " ms", 80),
                column("成本", entry -> "$" + entry.costUsd(), 80),
                column("终止", AuditEntry::stopReason, 70),
                column("输入摘要", entry -> truncate(entry.inputText(), 48), 300));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, sel) -> showDetail(sel));

        inputArea.setEditable(false);
        inputArea.setWrapText(true);
        inputArea.setPromptText("选中行 → 此处显示输入全文");
        toolArea.setEditable(false);
        toolArea.setWrapText(true);
        toolArea.setPromptText("选中行 → 此处显示工具调用序列表");
        Label detailTitle = new Label("详情"); detailTitle.getStyleClass().add("status");
        HBox detailArea = new HBox(8, inputArea, toolArea);
        HBox.setHgrow(inputArea, Priority.ALWAYS);
        HBox.setHgrow(toolArea, Priority.ALWAYS);
        inputArea.setPrefWidth(440);
        toolArea.setPrefWidth(520);
        VBox detail = new VBox(4, detailTitle, detailArea);
        detail.setPadding(new Insets(6, 0, 0, 0));

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(table);
        root.setBottom(detail);
        return root;
    }

    private void applyPreset() {
        String preset = presetCombo.getValue();
        LocalDate today = LocalDate.now();
        boolean custom = "自定义".equals(preset);
        fromPicker.setDisable(!custom);
        toPicker.setDisable(!custom);
        if (!custom) {
            int daysBack = "今天".equals(preset) ? 0 : "近 30 天".equals(preset) ? 29 : 6;
            fromPicker.setValue(today.minusDays(daysBack));
            toPicker.setValue(today);
        }
    }

    /** 按范围读盘（虚拟线程）→ 回填 loaded → 应用过滤 */
    private void refreshRange() {
        LocalDate from = fromPicker.getValue() == null ? LocalDate.now() : fromPicker.getValue();
        LocalDate to = toPicker.getValue() == null ? LocalDate.now() : toPicker.getValue();
        if (from.isAfter(to)) {
            Toast.show(stageOf(presetCombo), "开始日期不能晚于结束日期", false);
            return;
        }
        Instant start = from.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant end = to.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant();
        statusLabel.setText("加载审计日志…");
        Thread.ofVirtual().name("omniforge-audit-query", 0).start(() -> {
            List<AuditEntry> result = auditLogService.query(start, end);
            Platform.runLater(() -> {
                loaded = result;
                modelFilter.getItems().setAll(distinctModels(result));
                if (!modelFilter.getItems().contains(modelFilter.getValue())) {
                    modelFilter.setValue(null);
                }
                applyFilters();
            });
        });
    }

    private static List<String> distinctModels(List<AuditEntry> entries) {
        return entries.stream().map(AuditEntry::modelAlias).filter(Objects::nonNull)
                .distinct().sorted().toList();
    }

    private void applyFilters() {
        String userKw = userFilter.getText() == null ? "" : userFilter.getText().trim().toLowerCase();
        String model = modelFilter.getValue();
        String type = typeFilter.getValue();
        boolean onlyTool = hasToolFilter.isSelected();
        boolean onlyAbnormal = abnormalFilter.isSelected();
        filtered = loaded.stream()
                .filter(e -> userKw.isEmpty() || (e.user() != null && e.user().toLowerCase().contains(userKw)))
                .filter(e -> model == null || model.isEmpty() || model.equals(e.modelAlias()))
                .filter(e -> type == null || type.isEmpty() || type.equals(e.requestType()))
                .filter(e -> !onlyTool || (e.toolCalls() != null && !e.toolCalls().isEmpty()))
                .filter(e -> !onlyAbnormal || !"COMPLETED".equalsIgnoreCase(e.stopReason()))
                .collect(Collectors.toList());
        page = 0;
        renderTable();
    }

    private void renderTable() {
        int total = filtered.size();
        int maxPage = Math.max(0, (total + PAGE_SIZE - 1) / PAGE_SIZE - 1);
        if (page > maxPage) {
            page = maxPage;
        }
        int from = page * PAGE_SIZE;
        int to = Math.min(total, from + PAGE_SIZE);
        List<AuditEntry> visible = from < total ? filtered.subList(from, to) : List.of();
        table.getItems().setAll(visible);
        pageLabel.setText("第 " + (page + 1) + "/" + (maxPage + 1) + " 页 · 共 " + total + " 条（显示 " + from + "–" + to + "）");
        statusLabel.setText(summaryLine(filtered));
    }

    private static String summaryLine(List<AuditEntry> entries) {
        if (entries.isEmpty()) {
            return "无匹配审计记录。";
        }
        long abnormal = entries.stream().filter(e -> !"COMPLETED".equalsIgnoreCase(e.stopReason())).count();
        double cost = entries.stream().mapToDouble(AuditEntry::costUsd).sum();
        long avgMs = Math.round(entries.stream().mapToLong(AuditEntry::durationMs).average().orElse(0));
        String topModel = entries.stream().collect(Collectors.groupingBy(AuditEntry::modelAlias,
                Collectors.counting())).entrySet().stream()
                .max(Comparator.comparingLong(java.util.Map.Entry::getValue))
                .map(e -> e.getKey() + "×" + e.getValue()).orElse("—");
        return String.format("请求 %d ｜ 异常 %d ｜ 总成本 $%.4f ｜ 平均耗时 %d ms ｜ Top 模型 %s",
                entries.size(), abnormal, cost, avgMs, topModel);
    }

    private void showDetail(AuditEntry entry) {
        if (entry == null) {
            return;
        }
        inputArea.setText(entry.inputText());
        StringBuilder sb = new StringBuilder();
        List<AuditEntry.AuditToolCall> calls = entry.toolCalls();
        if (calls == null || calls.isEmpty()) {
            sb.append("（无工具调用）");
        } else {
            for (AuditEntry.AuditToolCall call : calls) {
                sb.append("→ ").append(call.toolName()).append(" [").append(call.status())
                        .append("] ").append(call.durationMs()).append(" ms\n");
                if (call.arguments() != null && !call.arguments().isBlank()) {
                    sb.append("  参数: ").append(truncate(call.arguments(), 240)).append('\n');
                }
                if (call.result() != null && !call.result().isBlank()) {
                    sb.append("  结果: ").append(truncate(call.result(), 240)).append('\n');
                }
            }
        }
        toolArea.setText(sb.toString().trim());
    }

    private void exportCsv(Window owner) {
        if (filtered.isEmpty()) {
            Toast.show(owner, "当前无筛选结果可导出", false);
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出审计记录 CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        chooser.setInitialFileName("audit-export.csv");
        File target = chooser.showSaveDialog(owner);
        if (target == null) {
            return;
        }
        Thread.ofVirtual().name("omniforge-audit-export", 0).start(() -> {
            try {
                StringBuilder sb = new StringBuilder(
                        "timestamp,type,model,user,session,runId,durationMs,costUsd,stopReason,tokensIn,tokensOut,tools,input\n");
                for (AuditEntry e : filtered) {
                    sb.append(csv(e.timestamp() == null ? "" : e.timestamp().toString()))
                            .append(',').append(csv(e.requestType()))
                            .append(',').append(csv(e.modelAlias()))
                            .append(',').append(csv(e.user()))
                            .append(',').append(csv(e.sessionId()))
                            .append(',').append(csv(e.runId()))
                            .append(',').append(e.durationMs())
                            .append(',').append(e.costUsd())
                            .append(',').append(csv(e.stopReason()))
                            .append(',').append(e.inputTokens()).append(',').append(e.outputTokens())
                            .append(',').append(csv(e.toolCalls().stream().map(AuditEntry.AuditToolCall::toolName)
                                    .collect(Collectors.joining(";"))))
                            .append(',').append(csv(truncate(e.inputText(), 2000)))
                            .append('\n');
                }
                Files.writeString(target.toPath(), sb.toString());
                Platform.runLater(() -> Toast.show(owner, "已导出 " + filtered.size() + " 条审计记录", true));
            } catch (IOException ex) {
                log.warn("审计导出失败：{}", ex.getMessage());
                Platform.runLater(() -> Toast.show(owner, "导出失败：" + ex.getMessage(), false));
            }
        });
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\"", "\"\"");
        return v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 ? "\"" + v + "\"" : v;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 表单小标签（status 样式） */
    private static Label label(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("status");
        return label;
    }

    // ================= 品牌白标页 =================

    private VBox buildBrandPane() {
        Path configDir = BrandingManager.configDir();
        brandPathField.setEditable(false);
        brandPathField.setText(configDir.resolve("branding.yml").toString());
        brandPathField.setMaxWidth(Double.MAX_VALUE);

        BrandingSettings current = new BrandingSettingsStore().load(configDir.resolve("branding.yml"));

        appNameField.setPromptText("应用名称（空 = OmniForge）");
        appNameField.setText(current.appName());

        defaultColor.setSelected(current.themeColor().isEmpty());
        if (!current.themeColor().isEmpty()) {
            colorPicker.setValue(parseHex(current.themeColor()));
        } else {
            colorPicker.setValue(Color.web(BrandingSettings.DEFAULT_THEME_COLOR));
        }
        colorPicker.setDisable(defaultColor.isSelected());
        defaultColor.setOnAction(e -> colorPicker.setDisable(defaultColor.isSelected()));

        logoPathField.setEditable(false);
        logoPathField.setPrefWidth(420);
        logoPathField.setText(current.logoPath());
        Button chooseLogo = new Button("选择文件…");
        chooseLogo.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择 Logo 图片（png/jpg/gif/bmp）");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("图片",
                    "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
            File file = chooser.showOpenDialog(stageOf(chooseLogo));
            if (file != null) {
                logoPathField.setText(file.getAbsolutePath());
                refreshBrandPreview();
            }
        });
        Button clearLogo = new Button("清除 Logo");
        clearLogo.setOnAction(e -> {
            logoPathField.clear();
            refreshBrandPreview();
        });
        Button resetBrand = new Button("恢复官方默认");
        resetBrand.setTooltip(new javafx.scene.control.Tooltip("应用名 OmniForge、默认品牌蓝、无 Logo"));
        resetBrand.setOnAction(e -> {
            appNameField.clear();
            defaultColor.setSelected(true);
            colorPicker.setValue(Color.web(BrandingSettings.DEFAULT_THEME_COLOR));
            logoPathField.clear();
            refreshBrandPreview();
        });

        // 保存前实时预览：应用名 + 主题色 + Logo 缩略
        brandPreview.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        brandPreview.setMaxWidth(Double.MAX_VALUE);
        logoPreview.setFitHeight(40);
        logoPreview.setPreserveRatio(true);
        javafx.scene.control.ScrollPane previewBox = new javafx.scene.control.ScrollPane();
        HBox logoRow = new HBox(10, logoPreview, brandPreview);
        logoRow.setAlignment(Pos.CENTER_LEFT);
        previewBox.setContent(logoRow);
        previewBox.setFitToWidth(true);
        previewBox.setPrefHeight(90);
        previewBox.getStyleClass().add("chat-scroll");
        Label previewTitle = new Label("预览（保存前）"); previewTitle.getStyleClass().add("status");
        VBox previewPanel = new VBox(4, previewTitle, previewBox);
        previewPanel.getStyleClass().add("config-card");
        previewPanel.setPadding(new Insets(8));

        appNameField.textProperty().addListener((o, a, b) -> refreshBrandPreview());
        colorPicker.valueProperty().addListener((o, a, b) -> { if (!defaultColor.isSelected()) refreshBrandPreview(); });
        defaultColor.selectedProperty().addListener((o, a, b) -> refreshBrandPreview());

        Button saveButton = new Button("💾 保存并应用");
        saveButton.getStyleClass().add("primary");
        saveButton.setOnAction(e -> saveBranding(saveButton));

        Label note = new Label("保存后立即写 branding.yml 并热应用：主题色/Logo/标题即时更新。");
        note.getStyleClass().add("status");

        VBox form = new VBox(10,
                labeled("branding.yml 位置", brandPathField),
                labeled("应用名称", appNameField),
                new HBox(12, labeled("主题色", colorPicker), defaultColor),
                new HBox(12, labeled("Logo 路径", logoPathField), chooseLogo, clearLogo),
                resetBrand,
                previewPanel,
                saveButton,
                note);
        form.setPadding(new Insets(12));
        form.setPrefWidth(Double.MAX_VALUE);
        refreshBrandPreview();
        if (proEnabled) {
            return form;
        }
        // CE：品牌白标整页签为 Pro 功能——编辑区整体禁用，仅引导激活（决定：页签可见但禁用）
        form.setDisable(true);
        Label proNotice = new Label("品牌白标（应用名/主题色/Logo）为 Pro 授权功能。"
                + "Community 版仅可查看当前品牌，编辑已禁用。");
        proNotice.getStyleClass().add("status");
        proNotice.setWrapText(true);
        Button activateButton = new Button("前往激活 Pro");
        activateButton.getStyleClass().add("primary");
        activateButton.setOnAction(e -> {
            if (onActivatePro != null) {
                onActivatePro.run();
            }
        });
        HBox activateRow = new HBox(activateButton);
        activateRow.setAlignment(Pos.CENTER_LEFT);
        VBox outer = new VBox(10, proNotice, activateRow, form);
        outer.setPadding(new Insets(12));
        return outer;
    }

    private static HBox labeled(String text, javafx.scene.Node control) {
        Label label = new Label(text);
        label.getStyleClass().add("status");
        label.setPrefWidth(100);
        HBox box = new HBox(8, label, control);
        box.setAlignment(Pos.CENTER_LEFT);
        if (control instanceof TextField || control instanceof TextArea) {
            HBox.setHgrow((javafx.scene.layout.Region) control, Priority.ALWAYS);
        }
        return box;
    }

    private void refreshBrandPreview() {
        String name = appNameField.getText() == null || appNameField.getText().isBlank()
                ? BrandingSettings.DEFAULT_APP_NAME : appNameField.getText().trim();
        Color color = defaultColor.isSelected() ? Color.web(BrandingSettings.DEFAULT_THEME_COLOR)
                : colorPicker.getValue();
        brandPreview.setText("◆ " + name);
        brandPreview.setTextFill(color);
        String logo = logoPathField.getText();
        if (logo != null && !logo.isBlank()) {
            try {
                Image image = new Image(Paths.get(logo).toUri().toString());
                logoPreview.setImage(image.isError() ? null : image);
            } catch (RuntimeException e) {
                logoPreview.setImage(null);
            }
        } else {
            logoPreview.setImage(null);
        }
    }

    private void saveBranding(javafx.scene.Node source) {
        Path configDir = BrandingManager.configDir();
        Path brandingFile = configDir.resolve("branding.yml");
        String color = defaultColor.isSelected() ? "" : hex(colorPicker.getValue());
        String appName = appNameField.getText() == null ? "" : appNameField.getText();
        String logo = logoPathField.getText() == null ? "" : logoPathField.getText().trim();
        BrandingSettings settings = new BrandingSettings(appName, logo, color);
        try {
            new BrandingSettingsStore().save(brandingFile, settings);
            BrandingManager.reload();
            com.omniforge.ui.theme.ThemeManager.reapplyAll();
            if (onBrandingChanged != null) {
                onBrandingChanged.run();
            }
            Toast.show(stageOf(source), "品牌已保存并应用（重启后仍生效）", true);
        } catch (IOException e) {
            log.warn("品牌保存失败：{}", e.getMessage());
            Toast.show(stageOf(source), "保存失败：" + e.getMessage(), false);
        }
    }

    private static String hex(Color color) {
        return String.format("#%02X%02X%02X", Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }

    private static Color parseHex(String hex) {
        try {
            return Color.web(hex);
        } catch (RuntimeException e) {
            return Color.web(BrandingSettings.DEFAULT_THEME_COLOR);
        }
    }

    private static Stage stageOf(javafx.scene.Node node) {
        return (Stage) node.getScene().getWindow();
    }

    private static TableColumn<AuditEntry, String> column(String title,
                                                         java.util.function.Function<AuditEntry, String> value,
                                                         double width) {
        TableColumn<AuditEntry, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(data -> new ReadOnlyStringWrapper(value.apply(data.getValue())));
        return col;
    }
}
