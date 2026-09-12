package com.omniforge.ui.enterprise;

import com.omniforge.ui.Toast;
import com.omniforge.ui.enterprise.EnterpriseBridge.KbDocument;
import com.omniforge.ui.enterprise.EnterpriseBridge.KbSearchHit;
import com.omniforge.ui.enterprise.EnterpriseBridge.AuthExpiredException;
import com.omniforge.ui.theme.ThemeManager;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 企业知识库对话框（企业模式，对接服务端共享库）：文档清单 / 上传 / 检索 / 删除 / 改分类。
 * member/admin 可管理；viewer 只读（隐藏管理操作，服务端仍 403 兜底）。
 */
public final class EnterpriseKnowledgeDialog {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(EnterpriseKnowledgeDialog.class);

    /** 单例：已打开时置前复用，避免连点菜单叠出多个对话框（每个都触发 refresh → 请求风暴） */
    private static volatile EnterpriseKnowledgeDialog active;

    private final EnterpriseBridge bridge;
    private final boolean viewer;

    private final Stage stage = new Stage();
    private final TableView<KbDocument> docTable = new TableView<>();
    private final TableView<KbSearchHit> hitTable = new TableView<>();
    private final TextField searchField = new TextField();
    private final ComboBox<String> categoryCombo = new ComboBox<>();
    private final Label statusLabel = new Label();

    /** 文件名/格式过滤（客户端即时过滤，不额外打服务端） */
    private final TextField nameFilterField = new TextField();
    private final ComboBox<String> formatCombo = new ComboBox<>();
    private List<KbDocument> allDocs = List.of();

    /** 左侧导航树（全部 → 按分类 → 类型 两级）+ 当前导航过滤 */
    private final TreeView<String> navTree = new TreeView<>();
    private final java.util.IdentityHashMap<TreeItem<String>, String> navFilterMap =
            new java.util.IdentityHashMap<>();
    private volatile String navFilter;
    private boolean buildingNavTree;

    /** 单实例预览对话框（重复预览先关旧的，避免堆积弹窗） */
    private javafx.scene.control.Dialog<Void> previewDialog;

    /** 多选删除按钮（member/admin；随选中数更新） */
    private final Button deleteSelectedBtn = new Button("删除选中");
    /** 预览选中（任何角色：先选记录再点按钮；行内预览按钮无需选中） */
    private final Button previewSelectedBtn = new Button("预览选中");
    private boolean deletingSelected;

    /** refresh 合并锁：任一时刻至多一个清单请求在途，期间新触发合并为一次（完成后补刷） */
    private final Object refreshLock = new Object();
    private boolean refreshRunning;
    private boolean refreshQueued;

    /** toast 防刷：连续失败只提示一次/3s，避免 FX 线程被海量弹窗拖死 */
    private volatile long lastToastAt;

    private EnterpriseKnowledgeDialog(EnterpriseBridge bridge, boolean viewer) {
        this.bridge = bridge;
        this.viewer = viewer;
    }

    private boolean toastThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastToastAt < 3000) {
            return true;
        }
        lastToastAt = now;
        return false;
    }

    public static void show(Stage owner, EnterpriseBridge bridge, boolean viewer) {
        EnterpriseKnowledgeDialog current = active;
        if (current != null && current.stage.isShowing()) {
            current.stage.toFront();
            return;
        }
        EnterpriseKnowledgeDialog dialog = new EnterpriseKnowledgeDialog(bridge, viewer);
        active = dialog;
        dialog.stage.setOnHidden(event -> {
            if (active == dialog) {
                active = null;
            }
        });
        dialog.open(owner);
    }

    private void open(Stage owner) {
        stage.setTitle("📚 企业知识库（服务端共享库）");
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        VBox center = buildRoot();
        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(buildNav(), center);
        split.setDividerPositions(0.20);
        stage.setScene(new Scene(split, 1240, 720));
        ThemeManager.attach(stage.getScene());
        refresh();
        stage.show();
    }

    /** 左侧导航：全部文档 / 分类（一级）→ 类型（二级） */
    private javafx.scene.Node buildNav() {
        navTree.setPrefWidth(240);
        navTree.setMinWidth(200);
        navTree.setFixedCellSize(26);
        TreeItem<String> empty = new TreeItem<>("📄 文档导航");
        navTree.setRoot(empty);
        navTree.setShowRoot(false);
        navTree.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (buildingNavTree || newV == null) {
                return;
            }
            // 点选即展开（分类/类型节点自身与其祖先都展开，符合"点一下打开"直觉）
            if (newV != navTree.getRoot()) {
                expandChain(newV);
            }
            String filter = navFilterMap.get(newV);
            navFilter = filter == null || "none".equals(filter) ? null : filter;
            nameFilterField.clear();
            formatCombo.getSelectionModel().clearSelection();
            formatCombo.setValue(null);
            applyDocFilter();
        });
        Label hint = new Label("按分类 / 类型归类浏览");
        hint.getStyleClass().add("muted");
        VBox nav = new VBox(6, hint, navTree);
        VBox.setVgrow(navTree, Priority.ALWAYS);
        return nav;
    }

    /** 重建左侧导航树：全部文档 → 分类（一级）→ 类型（二级）；保留原展开状态与选中 */
    private void updateNavTree() {
        String keep = navFilter;
        TreeItem<String> oldRoot = navTree.getRoot();
        java.util.Set<String> expandedCats = new java.util.HashSet<>();
        if (oldRoot != null) {
            for (TreeItem<String> cat : oldRoot.getChildren()) {
                String f = navFilterMap.get(cat);
                if (f != null && cat.isExpanded()) {
                    expandedCats.add(f);
                }
            }
        }
        TreeItem<String> root = new TreeItem<>("全部文档 (" + allDocs.size() + ")");
        navFilterMap.put(root, "none");
        // 分类（一级）→ 类型（二级）
        java.util.TreeMap<String, java.util.TreeMap<String, Integer>> catType =
                new java.util.TreeMap<>();
        for (KbDocument doc : allDocs) {
            String cat = doc.category() == null || doc.category().isBlank()
                    ? "（未分类）" : doc.category();
            String ext = extension(doc.fileName());
            catType.computeIfAbsent(cat, k -> new java.util.TreeMap<>())
                    .merge(ext, 1, Integer::sum);
        }
        for (var catEntry : catType.entrySet()) {
            String cat = catEntry.getKey();
            int catTotal = catEntry.getValue().values().stream().mapToInt(Integer::intValue).sum();
            TreeItem<String> catItem = new TreeItem<>(cat + "  (" + catTotal + ")");
            navFilterMap.put(catItem, "cat:" + cat);
            for (var typeEntry : catEntry.getValue().entrySet()) {
                String type = typeEntry.getKey();
                TreeItem<String> typeItem = new TreeItem<>(
                        (type.isEmpty() ? "（无扩展名）" : type) + "  (" + typeEntry.getValue() + ")");
                navFilterMap.put(typeItem, "cat:" + cat + ":type:" + type);
                catItem.getChildren().add(typeItem);
            }
            catItem.setExpanded(expandedCats.contains("cat:" + cat));
            root.getChildren().add(catItem);
        }
        buildingNavTree = true;
        try {
            navTree.setRoot(root);
            navTree.setShowRoot(true);
            root.setExpanded(true);
            if (keep != null) {
                TreeItem<String> sel = findLeaf(root, keep);
                if (sel != null) {
                    expandChain(sel);
                    navTree.getSelectionModel().select(sel);
                }
            } else {
                navTree.getSelectionModel().select(root);
            }
        } finally {
            buildingNavTree = false;
        }
    }

    /** 展开自身及全部祖先（点选/恢复选中时保证可见） */
    private static void expandChain(TreeItem<String> item) {
        TreeItem<String> node = item;
        while (node != null) {
            node.setExpanded(true);
            node = node.getParent();
        }
    }

    private TreeItem<String> findLeaf(TreeItem<String> root, String filter) {
        for (TreeItem<String> cat : root.getChildren()) {
            if (filter.equals(navFilterMap.get(cat))) {
                return cat;
            }
            for (TreeItem<String> type : cat.getChildren()) {
                if (filter.equals(navFilterMap.get(type))) {
                    return type;
                }
            }
        }
        return null;
    }

    private VBox buildRoot() {
        Button refreshButton = new Button("刷新");
        Button uploadButton = new Button("＋ 上传文档");
        uploadButton.getStyleClass().add("primary");
        Button searchButton = new Button("检索");
        searchField.setPromptText("语义检索内容（支持按分类）…");
        searchField.setPrefWidth(220);
        categoryCombo.setPromptText("分类过滤");
        categoryCombo.setPrefWidth(160);
        statusLabel.getStyleClass().add("status");

        HBox toolbar = new HBox(8, refreshButton, uploadButton, previewSelectedBtn,
                deleteSelectedBtn, searchField, categoryCombo,
                searchButton, new Region(), statusLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(toolbar.getChildren().get(8), Priority.ALWAYS);
        deleteSelectedBtn.getStyleClass().add("danger");
        deleteSelectedBtn.setVisible(!viewer);
        deleteSelectedBtn.setManaged(!viewer);
        previewSelectedBtn.setVisible(true);
        previewSelectedBtn.setManaged(true);

        docTable.setPlaceholder(new Label("暂无文档"));
        docTable.setPrefHeight(240);
        // 固定行高：500+ 行虚拟化布局提速（展开/滚动不再重排大量行）
        docTable.setFixedCellSize(36);
        docTable.getSelectionModel().setSelectionMode(
                javafx.scene.control.SelectionMode.MULTIPLE);
        docTable.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener.Change<? extends KbDocument> change) -> {
                    int n = docTable.getSelectionModel().getSelectedItems().size();
                    deleteSelectedBtn.setText(n == 0 ? "删除选中" : "删除选中(" + n + ")");
                    previewSelectedBtn.setText(n <= 1 ? "预览选中" : "预览选中(" + n + ")");
                });
        // 双击行 = 预览（无需先点"预览"按钮）
        docTable.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2
                    && docTable.getSelectionModel().getSelectedItem() != null) {
                previewDocument(docTable.getSelectionModel().getSelectedItem());
                event.consume();
            }
        });

        // 文件名 + 格式过滤行（用户需求：知识库可按文件名/格式检索）
        nameFilterField.setPromptText("按文件名过滤…");
        nameFilterField.setPrefWidth(200);
        formatCombo.setPromptText("格式：全部");
        formatCombo.setPrefWidth(140);
        Label filterHint = new Label("格式含 pdf / docx / csv / png 等");
        filterHint.getStyleClass().add("muted");
        HBox filterBar = new HBox(8, nameFilterField, formatCombo, filterHint, new Region());
        filterBar.setAlignment(Pos.CENTER_LEFT);
        nameFilterField.textProperty().addListener((obs, oldV, newV) -> applyDocFilter());
        formatCombo.setOnAction(e -> applyDocFilter());
        docTable.getColumns().addAll(
                col("文件名", 320, KbDocument::fileName),
                col("分类", 150, doc -> doc.category() == null || doc.category().isBlank()
                        ? "（未分类）" : doc.category()),
                col("切片", 60, KbDocument::chunks),
                col("字符", 90, KbDocument::chars));
        TableColumn<KbDocument, Void> actions = new TableColumn<>("操作");
        actions.setPrefWidth(330);
        actions.setCellFactory(c -> new TableCell<>() {

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                KbDocument row = getTableRow() == null ? null : getTableRow().getItem();
                if (empty || row == null) {
                    setGraphic(null);
                    return;
                }
                // 预览/原件：任何角色（viewer 只读亦可）；补OCR/改分类：仅非 viewer
                Button preview = new Button("预览");
                Button original = new Button("原件");
                preview.setOnAction(e -> previewDocument(row));
                original.setOnAction(e -> openOriginal(row));
                HBox box;
                if (viewer) {
                    box = new HBox(4, preview, original);
                } else {
                    Button reocrBtn = null;
                    String ext = extension(row.fileName());
                    if ("pdf".equals(ext) || java.util.Set.of("jpg", "jpeg", "png", "gif",
                            "bmp", "webp", "tif", "tiff").contains(ext)) {
                        reocrBtn = new Button("补OCR");
                        reocrBtn.setTooltip(new javafx.scene.control.Tooltip(
                                "需该文档已留存原件（新上传才有）；重新识别并替换文档内容"));
                        reocrBtn.setOnAction(e -> reOcr(row));
                    }
                    Button catBtn = new Button("改分类");
                    catBtn.setOnAction(e -> changeCategory(row));
                    HBox edit = new HBox(4, preview, original);
                    if (reocrBtn != null) {
                        edit.getChildren().add(reocrBtn);
                    }
                    edit.getChildren().add(catBtn);
                    box = edit;
                }
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });
        docTable.getColumns().add(actions);

        hitTable.setPlaceholder(new Label("检索结果（回车或点检索）"));
        hitTable.setPrefHeight(200);
        hitTable.getColumns().addAll(
                col("命中文件", 260, KbSearchHit::fileName),
                col("相关度", 80, h -> String.format("%.3f", h.score())),
                col("片段", 460, h -> h.content()));

        refreshButton.setOnAction(e -> refresh());
        uploadButton.setOnAction(e -> upload());
        previewSelectedBtn.setOnAction(e -> {
            KbDocument row = docTable.getSelectionModel().getSelectedItem();
            if (row == null) {
                Toast.show(null, "请先在表格中选择要预览的文档（或双击行直接预览）", false);
                return;
            }
            previewDocument(row);
        });
        deleteSelectedBtn.setOnAction(e -> deleteSelected());
        searchButton.setOnAction(e -> search());
        searchField.setOnAction(e -> search());
        categoryCombo.setOnAction(e -> refresh());
        uploadButton.setVisible(!viewer);
        uploadButton.setManaged(!viewer);

        VBox pane = new VBox(8, toolbar, filterBar, docTable,
                new Label("语义检索（服务端库，Agent 同源）"), hitTable);
        VBox.setVgrow(docTable, Priority.ALWAYS);
        return pane;
    }

    /** 合并式刷新：在途时置 pending，完成后补一次；避免连点/多触发造成请求风暴 */
    private void refresh() {
        synchronized (refreshLock) {
            if (refreshRunning) {
                refreshQueued = true;
                return;
            }
            refreshRunning = true;
        }
        async(() -> {
            List<KbDocument> docs = bridge.kbDocuments();
            List<String> categories = bridge.kbCategories();
            return new Object[]{docs, categories};
        }, result -> {
            @SuppressWarnings("unchecked")
            List<KbDocument> docs = (List<KbDocument>) result[0];
            @SuppressWarnings("unchecked")
            List<String> categories = (List<String>) result[1];
            boolean dataChanged = !docs.equals(allDocs);
            allDocs = List.copyOf(docs);
            refreshFormatOptions();
            if (dataChanged) {
                updateNavTree();
            } else {
                // 数据未变：只重放过滤，保持树展开状态不被打断
                applyDocFilter();
            }
            categoryCombo.getItems().clear();
            categoryCombo.getItems().add("（全部分类）");
            categoryCombo.getItems().addAll(categories);
            categoryCombo.getSelectionModel().selectFirst();
            statusLabel.setText("共 " + docs.size() + " 篇文档");
            applyDocFilter(); // 最后执行：过滤态时覆盖为 "x / n（已过滤）"
        }, this::finishRefresh);
    }

    private void finishRefresh() {
        boolean again;
        synchronized (refreshLock) {
            refreshRunning = false;
            again = refreshQueued;
            refreshQueued = false;
        }
        if (again) {
            refresh();
        }
    }

    /** 从全量清单收集格式（扩展名）下拉项 */
    private void refreshFormatOptions() {
        String selected = formatCombo.getValue();
        List<String> formats = allDocs.stream()
                .map(KbDocument::fileName)
                .map(EnterpriseKnowledgeDialog::extension)
                .filter(ext -> !ext.isEmpty())
                .distinct()
                .sorted()
                .toList();
        formatCombo.getItems().clear();
        formatCombo.getItems().addAll(formats);
        if (selected != null && formats.contains(selected)) {
            formatCombo.setValue(selected);
        } else {
            formatCombo.getSelectionModel().clearSelection();
            formatCombo.setValue(null);
        }
    }

    /** 按导航（分类/类型）+ 文件名关键字 + 格式过滤（客户端即时，服务端无额外请求） */
    private void applyDocFilter() {
        String keyword = nameFilterField.getText() == null ? "" : nameFilterField.getText().trim();
        String format = formatCombo.getValue();
        String filter = navFilter;
        String kw = keyword.toLowerCase(java.util.Locale.ROOT);
        List<KbDocument> filtered = allDocs.stream()
                .filter(doc -> matchesNav(doc, filter))
                .filter(doc -> kw.isEmpty()
                        || doc.fileName().toLowerCase(java.util.Locale.ROOT).contains(kw))
                .filter(doc -> format == null || format.isEmpty()
                        || format.equalsIgnoreCase(extension(doc.fileName())))
                .toList();
        docTable.getItems().setAll(filtered);
        boolean filtering = filter != null || !kw.isEmpty() || (format != null && !format.isEmpty());
        docTable.setPlaceholder(new Label(allDocs.isEmpty() ? "暂无文档"
                : (filtering ? "无匹配文档（试试放宽文件名或格式条件）" : "暂无文档")));
        if (filtering) {
            statusLabel.setText(filtered.size() + " / " + allDocs.size() + " 篇（已过滤）");
        } else {
            statusLabel.setText("共 " + allDocs.size() + " 篇文档");
        }
    }

    /** 导航过滤匹配：一级 "cat:c"；二级 "cat:c:type:t"（t 空 = 无扩展名文档）；null=全部 */
    private static boolean matchesNav(KbDocument doc, String filter) {
        if (filter == null) {
            return true;
        }
        String[] parts = filter.split(":", -1);
        if (parts.length >= 2 && "cat".equals(parts[0])) {
            String cat = doc.category() == null || doc.category().isBlank()
                    ? "（未分类）" : doc.category();
            if (!parts[1].equals(cat)) {
                return false;
            }
            if (parts.length >= 4 && "type".equals(parts[2])) {
                String wantType = parts[3];
                String docType = extension(doc.fileName());
                return wantType.isEmpty() ? docType.isEmpty() : wantType.equalsIgnoreCase(docType);
            }
            return true;
        }
        return true;
    }

    /** 多选批量删除（member/admin；逐个调服务端删除后刷新） */
    private void deleteSelected() {
        if (viewer || deletingSelected) {
            return;
        }
        List<KbDocument> rows = new ArrayList<>(
                docTable.getSelectionModel().getSelectedItems());
        if (rows.isEmpty()) {
            Toast.show(null, "请先勾选要删除的文档（可多选）", false);
            return;
        }
        if (!confirm("确定删除选中的 " + rows.size() + " 篇文档及其全部切片？")) {
            return;
        }
        deletingSelected = true;
        deleteSelectedBtn.setDisable(true);
        statusLabel.setText("正在删除 " + rows.size() + " 篇…");
        Thread.ofVirtual().start(() -> {
            int failed = 0;
            for (KbDocument row : rows) {
                try {
                    bridge.kbDeleteDocument(row.fileName());
                } catch (Exception e) {
                    failed++;
                    log.warn("批量删除失败（继续）：{}", row.fileName(), e);
                }
            }
            final int totalFailed = failed;
            Platform.runLater(() -> {
                deletingSelected = false;
                deleteSelectedBtn.setDisable(false);
                deleteSelectedBtn.setText("删除选中");
                if (totalFailed == 0) {
                    Toast.show(null, "已删除 " + rows.size() + " 篇文档", true);
                } else {
                    Toast.show(null, "删除完成，失败 " + totalFailed + " 篇", false);
                }
                refresh();
            });
        });
    }

    /** 文件名扩展名（小写；无扩展名返回空串） */
    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    private void upload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要入库的文档");
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length > 10L * 1024 * 1024) {
                Toast.show(null, "文件超过 10 MB 上限", false);
                return;
            }
            // 分类输入对话框必须在 FX 线程弹出（showAndWait 仅允许 FX 线程），
            // 故先取分类再进后台异步上传（否则后台线程弹窗会抛 IllegalStateException → “操作失败”）
            String category = promptCategory();
            statusLabel.setText("正在解析/向量化入库…（大文件稍候）");
            async(() -> bridge.kbUpload(file.getName(), bytes, category), count -> {
                statusLabel.setText("入库完成（" + count + " 切片）");
                Toast.show(null, "已入库：" + file.getName(), true);
                refresh();
            });
        } catch (Exception e) {
            Toast.show(null, "读取文件失败：" + e.getMessage(), false);
        }
    }

    private String promptCategory() {
        TextField field = new TextField();
        javafx.scene.control.Dialog<String> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("入库分类（可选）");
        dialog.initOwner(stage);
        dialog.getDialogPane().setContent(new VBox(8,
                new Label("分类（留空 = 未分类）"), field));
        dialog.getDialogPane().getButtonTypes().addAll(
                ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? field.getText().trim() : null);
        ThemeManager.attachDialog(dialog);
        return dialog.showAndWait().orElse("");
    }

    private void search() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim();
        if (query.isEmpty()) {
            Toast.show(null, "请输入检索内容", false);
            return;
        }
        String selected = categoryCombo.getValue();
        final String category = selected != null && selected.startsWith("（") ? null : selected;
        statusLabel.setText("检索中…");
        hitTable.getItems().clear();
        async(() -> bridge.kbSearch(query, 8, category), hits -> {
            hitTable.getItems().setAll(hits);
            statusLabel.setText(hits.isEmpty() ? "未命中" : "命中 " + hits.size() + " 条");
            if (hits.isEmpty()) {
                Toast.show(null, "未检索到相关内容", true);
            }
        });
    }

    private void deleteDocument(KbDocument row) {
        if (!confirm("删除文档「" + row.fileName() + "」？")) {
            return;
        }
        async(() -> {
            bridge.kbDeleteDocument(row.fileName());
            return null;
        }, unused -> {
            Toast.show(null, "已删除", true);
            refresh();
        });
    }

    /** 预览：弹框立即出现（加载中…），文本就绪后填充；失败在框内提示；单实例（重复点击先关旧的） */
    private void previewDocument(KbDocument row) {
        if (previewDialog != null && previewDialog.isShowing()) {
            previewDialog.close();
        }
        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
        previewDialog = dialog;
        dialog.setTitle("预览：" + row.fileName());
        dialog.initOwner(stage);
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea();
        area.setEditable(false);
        area.setWrapText(true);
        area.setFocusTraversable(false);
        area.setStyle("-fx-font-family: 'Consolas','Microsoft YaHei',monospace; -fx-font-size: 13px;");
        Button openOriginalBtn = new Button("打开原件（若为旧数据无原件会提示）");
        openOriginalBtn.getStyleClass().add("primary");
        openOriginalBtn.setOnAction(e -> {
            dialog.close();
            openOriginal(row);
        });
        Label state = new Label("加载中…");
        state.getStyleClass().add("status");
        Label openBar = new Label();
        openBar.setGraphic(openOriginalBtn);
        VBox content = new VBox(6, state, openBar, area);
        VBox.setVgrow(area, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(960, 660);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ThemeManager.attachDialog(dialog);
        dialog.show();
        dialog.getDialogPane().requestFocus();
        Thread.ofVirtual().start(() -> {
            try {
                String text = bridge.kbDocumentContent(row.fileName());
                // 本地再截断（服务端已截 10 万）：避免超大文本一次性布局拖慢 FX 线程（忙光标闪烁）
                final String shown = text == null ? "" : text.length() > 30_000
                        ? text.substring(0, 30_000) + "\n\n…（本地预览截断，全文约 " + text.length() + " 字）"
                        : text;
                Platform.runLater(() -> {
                    area.setText(shown);
                    state.setText("");
                });
            } catch (EnterpriseBridge.AuthExpiredException e) {
                Platform.runLater(() -> {
                    dialog.close();
                    Toast.show(null, "登录已过期，请重新登录", false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    String err = msg(e);
                    if (err.contains("无可用文本")) {
                        state.setText("该文档没有可预览的文字层（扫描版 PDF / 纯图片），"
                                + "可点上方「打开原件」用系统程序查看");
                    } else if (err.contains("400") || err.contains("No message")) {
                        state.setText("文件名含特殊字符，文本预览被拒；可点「打开原件」查看（新上传文档有效）");
                    } else {
                        state.setText("预览失败：" + err);
                    }
                    state.getStyleClass().add("danger-text");
                });
            }
        });
    }

    /** 查看原件：下载原始文件到临时目录并用系统默认程序打开（viewer 只读可看） */
    private void openOriginal(KbDocument row) {
        statusLabel.setText("下载原件…");
        async(() -> {
            byte[] bytes = bridge.kbOriginalFile(row.fileName());
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("原件内容为空");
            }
            java.io.File tmp = java.io.File.createTempFile("omniforge-original-", suffixOf(row.fileName()));
            tmp.deleteOnExit();
            java.nio.file.Files.write(tmp.toPath(), bytes);
            return tmp;
        }, file -> {
            try {
                java.awt.Desktop.getDesktop().open(file);
                Toast.show(null, "已用系统默认程序打开原件", true);
            } catch (Exception e) {
                Toast.show(null, "打开原件失败：" + msg(e), false);
            } finally {
                statusLabel.setText("共 " + allDocs.size() + " 篇文档");
            }
        });
    }

    /** 补 OCR（留存原件的图片/PDF）：重新识别并替换文档内容 */
    private void reOcr(KbDocument row) {
        if (!confirm("对「" + row.fileName() + "」重新 OCR（替换现有内容）？\n"
                + "需该文档曾上传且服务端留存了原件；旧数据无原件会提示。")) {
            return;
        }
        statusLabel.setText("OCR 中…（扫描 PDF 按页数耗时，可稍候）");
        async(() -> {
            bridge.kbReOcr(row.fileName());
            return null;
        }, unused -> {
            Toast.show(null, "OCR 完成，内容已更新", true);
            refresh();
        });
    }

    /** 临时文件后缀（保留扩展名让系统按正确程序打开） */
    private static String suffixOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot);
            return ext.length() <= 12 ? ext : ".bin";
        }
        return ".bin";
    }

    private void changeCategory(KbDocument row) {
        javafx.scene.control.TextInputDialog prompt =
                new javafx.scene.control.TextInputDialog(row.category() == null
                        || row.category().isBlank() ? "" : row.category());
        prompt.setTitle("改分类");
        prompt.setHeaderText("「" + row.fileName() + "」的新分类（留空=未分类）");
        prompt.initOwner(stage);
        ThemeManager.attachDialog(prompt);
        Optional<String> name = prompt.showAndWait();
        if (name.isEmpty()) {
            return;
        }
        async(() -> {
            bridge.kbUpdateCategory(row.fileName(), name.get().trim());
            return null;
        }, unused -> {
            Toast.show(null, "分类已更新", true);
            refresh();
        });
    }

    private boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle("确认");
        alert.initOwner(stage);
        ThemeManager.attachDialog(alert);
        return alert.showAndWait().map(ButtonType.OK::equals).orElse(false);
    }

    private <T> void async(io<T> io, java.util.function.Consumer<T> onSuccess) {
        async(io, onSuccess, null);
    }

    private <T> void async(io<T> io, java.util.function.Consumer<T> onSuccess, Runnable onFinally) {
        Thread.ofVirtual().start(() -> {
            try {
                T result = io.run();
                Platform.runLater(() -> {
                    try {
                        onSuccess.accept(result);
                    } catch (Exception e) {
                        Toast.show(null, "操作失败：" + msg(e), false);
                    } finally {
                        if (onFinally != null) {
                            onFinally.run();
                        }
                    }
                });
            } catch (AuthExpiredException e) {
                log.warn("企业知识库操作：登录已过期：{}", e.getMessage());
                Platform.runLater(() -> {
                    stage.close();
                    Toast.show(null, "登录已过期，请重新登录", false);
                    if (onFinally != null) {
                        onFinally.run();
                    }
                });
            } catch (Exception e) {
                log.error("企业知识库操作失败：{}", msg(e), e);
                Platform.runLater(() -> {
                    statusLabel.setText("操作失败");
                    if (!toastThrottled()) {
                        Toast.show(null, msg(e), false);
                    }
                    if (onFinally != null) {
                        onFinally.run();
                    }
                });
            }
        });
    }

    @FunctionalInterface
    private interface io<T> {
        T run() throws Exception;
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static <T> TableColumn<T, Object> col(String title, double width,
                                                  Function<T, Object> extractor) {
        TableColumn<T, Object> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(extractor.apply(cd.getValue())));
        return column;
    }
}
