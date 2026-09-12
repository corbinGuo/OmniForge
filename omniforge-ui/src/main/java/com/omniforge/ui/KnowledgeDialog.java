package com.omniforge.ui;

import com.omniforge.knowledge.KnowledgeService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库管理面板（C 级第 1 批任务 1 + 知识库分类 2026-09）：文档挂载（上传 + 进度显示）、
 * 按分类分组展示（折叠/删除整类/改分类）、查看全文、删除文档；数据经 {@link KnowledgeService}
 * （与 knowledge_search 工具共享同一向量存储）。
 */
final class KnowledgeDialog {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDialog.class);

    private final KnowledgeService knowledgeService;
    private final VBox docBox = new VBox(8);
    private final Label progressLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label storageLabel = new Label();
    private final HBox progressRow = new HBox(8, progressLabel, progressBar);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    /** 已折叠的分组（key = 原始分类值，含空串"未分类"） */
    private final Set<String> collapsedGroups = new LinkedHashSet<>();
    private Stage dialogStage;
    private Button uploadButton;
    private Button folderButton;

    private static final String UNCATEGORIZED_LABEL = "未分类";

    /** 分类展示文案：空串 = 未分类 */
    private static String categoryLabel(String category) {
        return category == null || category.isBlank() ? UNCATEGORIZED_LABEL : category;
    }

    KnowledgeDialog(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    void show(Window owner) {
        storageLabel.getStyleClass().add("status");
        progressLabel.getStyleClass().add("status");
        progressBar.setPrefWidth(240);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        progressRow.setVisible(false);
        progressRow.setManaged(false);

        uploadButton = new Button("➕ 上传文档（可多选）");
        uploadButton.getStyleClass().add("primary");
        uploadButton.setTooltip(new javafx.scene.control.Tooltip("按住 Ctrl/Shift 一次选多个文档"));
        uploadButton.setOnAction(event -> chooseAndUpload());
        folderButton = new Button("📁 上传文件夹");
        folderButton.setOnAction(event -> chooseAndUploadFolder());

        Button refreshButton = new Button("↻ 刷新");
        refreshButton.setOnAction(event -> refresh());

        Label hint = new Label(SUPPORT_HINT);
        hint.getStyleClass().add("status");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        // 标题行：← 返回在左上角，标题在左、操作按钮在右（状态文字单独一行——
        // 此前状态文字与按钮同排，长 Embedding 描述把按钮挤出对话框右缘不可见）
        Button backButton = new Button("← 返回");
        backButton.setOnAction(event -> {
            if (dialogStage != null) {
                dialogStage.close();
            }
        });
        Label title = new Label("📚 知识库");
        title.getStyleClass().add("empty-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox titleRow = new HBox(8, backButton, title, spacer, folderButton, uploadButton, refreshButton);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        // 状态文字独立行：允许换行，不再挤压按钮
        storageLabel.setWrapText(true);
        storageLabel.setMaxWidth(Double.MAX_VALUE);

        ScrollPane scroll = new ScrollPane(docBox);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("chat-scroll");

        // P1 重排：标题+操作 → 状态 → 内容
        VBox header = new VBox(4, titleRow, storageLabel, hint, progressRow);
        header.setPadding(new Insets(10, 10, 4, 10));
        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(scroll);

        dialogStage = new Stage();
        dialogStage.setTitle("OmniForge"); // 标题去重：页内已有「📚 知识库」
        dialogStage.setScene(new Scene(root, 720, 560));
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(owner);
        com.omniforge.ui.theme.ThemeManager.attach(dialogStage.getScene());
        refresh();
        dialogStage.show();
    }

    // ---------- 文档列表 ----------

    /** 用户友好状态文案（P1）：技术名 → 人话 */
    private String friendlyStatus() {
        String storage = knowledgeService.storageDescription()
                .replace("InMemory（非持久化降级，", "本地向量库 · ")
                .replace(" 条）", " 个切片）");
        String embedding = knowledgeService.embeddingDescription();
        String friendlyEmbedding;
        if (embedding.contains("就绪")) {
            friendlyEmbedding = "向量模型已就绪";
        } else if (embedding.contains("未初始化")) {
            friendlyEmbedding = "向量模型首次使用自动加载";
        } else {
            friendlyEmbedding = "向量模型未就绪（首次使用需联网下载）";
        }
        return "知识库：" + storage + " ｜ " + friendlyEmbedding;
    }

    private void refresh() {
        storageLabel.setText(friendlyStatus());
        List<KnowledgeService.DocumentInfo> documents;
        try {
            documents = knowledgeService.listDocuments();
        } catch (UnsupportedOperationException e) {
            Label unsupported = new Label("当前向量存储不支持文档管理：" + e.getMessage());
            unsupported.getStyleClass().add("status");
            docBox.getChildren().setAll(unsupported);
            return;
        }
        if (documents.isEmpty()) {
            // 空状态：提示 + 品牌色上传按钮（按钮就在提示下方，点击直接选文件）
            Label empty = new Label("尚无文档");
            empty.getStyleClass().add("empty-title");
            Label emptyHint = new Label("上传 Word/Excel/PPT/PDF 或 txt/md/csv 等文本后，"
                    + "Agent 的 knowledge_search 即可检索到内容");
            emptyHint.getStyleClass().add("status");
            emptyHint.setWrapText(true);
            emptyHint.setMaxWidth(480);
            Button uploadHere = new Button("➕ 上传文档");
            uploadHere.getStyleClass().add("primary");
            uploadHere.setOnAction(event -> chooseAndUpload());
            VBox emptyBox = new VBox(8, empty, emptyHint, uploadHere);
            emptyBox.setAlignment(Pos.CENTER);
            emptyBox.setPadding(new Insets(40));
            docBox.getChildren().setAll(emptyBox);
            return;
        }
        docBox.getChildren().setAll(buildGrouped(documents));
    }

    /** 按分类分组：未分类排最前，命名分类按名称升序；组内文件沿用 fileName 升序 */
    private List<VBox> buildGrouped(List<KnowledgeService.DocumentInfo> documents) {
        Map<String, List<KnowledgeService.DocumentInfo>> groups = new LinkedHashMap<>();
        for (KnowledgeService.DocumentInfo doc : documents) {
            groups.computeIfAbsent(doc.category(), key -> new ArrayList<>()).add(doc);
        }
        List<String> ordered = new ArrayList<>(groups.keySet());
        ordered.sort((a, b) -> {
            boolean aBlank = a.isBlank();
            boolean bBlank = b.isBlank();
            if (aBlank != bBlank) {
                return aBlank ? -1 : 1;
            }
            return a.compareTo(b);
        });
        List<String> usedCategories = ordered.stream()
                .filter(category -> !category.isBlank())
                .toList();
        List<VBox> boxes = new ArrayList<>(ordered.size());
        for (String category : ordered) {
            boxes.add(buildGroupBox(category, groups.get(category), usedCategories));
        }
        return boxes;
    }

    /** 单组分组的折叠表头 + 文档行列表 */
    private VBox buildGroupBox(String category, List<KnowledgeService.DocumentInfo> docs,
                               List<String> usedCategories) {
        boolean open = !collapsedGroups.contains(category);
        Label arrow = new Label(open ? "▾" : "▸");
        arrow.getStyleClass().add("status");
        Label name = new Label("📁 " + categoryLabel(category));
        name.getStyleClass().add("status");
        Label count = new Label(docs.size() + " 个文件");
        count.getStyleClass().add("status");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button deleteAll = new Button("🗑 删除整类");
        deleteAll.getStyleClass().add("danger");
        deleteAll.setOnAction(event -> confirmDeleteCategory(category, docs.size()));

        HBox header = new HBox(6, arrow, name, count, spacer, deleteAll);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("config-card");
        header.setPadding(new Insets(6, 10, 6, 10));
        header.setCursor(Cursor.HAND);
        header.setOnMouseClicked(event -> {
            // 点按钮不折叠分组（点击经事件冒泡到表头时忽略）
            if (event.getTarget() instanceof Button) {
                return;
            }
            if (!collapsedGroups.add(category)) {
                collapsedGroups.remove(category);
            }
            refresh();
        });

        VBox body = new VBox(4);
        for (KnowledgeService.DocumentInfo doc : docs) {
            body.getChildren().add(docRow(doc, usedCategories));
        }
        body.setVisible(open);
        body.setManaged(open);
        return new VBox(header, body);
    }

    private HBox docRow(KnowledgeService.DocumentInfo info, List<String> usedCategories) {
        Label name = new Label("📄 " + info.fileName());
        name.setMaxWidth(Double.MAX_VALUE);
        Label meta = new Label(info.chunks() + " 切片 · " + info.chars() + " 字符");
        meta.getStyleClass().add("status");
        VBox text = new VBox(2, name, meta);
        HBox.setHgrow(text, Priority.ALWAYS);

        ComboBox<String> category = categoryCombo(info, usedCategories);

        Button view = new Button("查看");
        view.setOnAction(event -> showContent(info.fileName()));
        Button delete = new Button("删除");
        delete.getStyleClass().add("danger");
        delete.setOnAction(event -> confirmDelete(info.fileName()));

        HBox row = new HBox(8, text, category, view, delete);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("config-card");
        row.setPadding(new Insets(8, 10, 8, 10));
        return row;
    }

    /** 可编辑分类下拉：列出已用分类供选，也可手输新分类；未分类文档留空并显示提示文案 */
    private ComboBox<String> categoryCombo(KnowledgeService.DocumentInfo info, List<String> usedCategories) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setEditable(true);
        combo.setPrefWidth(150);
        combo.setPromptText(UNCATEGORIZED_LABEL);
        combo.setTooltip(new javafx.scene.control.Tooltip(
                "选择或输入分类后回车保存；清空后回车 = 回到未分类"));
        List<String> items = new ArrayList<>(usedCategories);
        String current = info.category();
        if (!current.isBlank() && !items.contains(current)) {
            items.add(current);
        }
        items.sort(Comparator.naturalOrder());
        combo.getItems().setAll(items);
        if (!current.isBlank()) {
            combo.setValue(current);
        }
        combo.setOnAction(event -> commitCategory(info, combo));
        return combo;
    }

    private void commitCategory(KnowledgeService.DocumentInfo info, ComboBox<String> combo) {
        String text = combo.getEditor().getText();
        String target = text == null ? "" : text.trim();
        String current = info.category() == null ? "" : info.category();
        if (target.equals(current)) {
            return;
        }
        try {
            int updated = knowledgeService.updateCategory(info.fileName(), target);
            // 延迟到本事件派发完成后再重建列表，避免在事件处理中移除正被操作的控件
            Platform.runLater(() -> {
                refresh();
                Toast.show(dialogStage, "「" + info.fileName() + "」已归类到「"
                        + categoryLabel(target) + "」（" + updated + " 个切片）", true);
            });
        } catch (UnsupportedOperationException e) {
            Toast.show(dialogStage, "当前向量存储不支持修改分类：" + e.getMessage(), false);
        }
    }

    // ---------- 查看 / 删除 ----------

    private void showContent(String fileName) {
        String content = knowledgeService.viewDocument(fileName);
        TextArea area = new TextArea(content);
        area.setEditable(false);
        area.setWrapText(true);
        ScrollPane scroll = new ScrollPane(area);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("chat-scroll");
        Stage preview = new Stage();
        preview.setTitle("预览：" + fileName);
        preview.setScene(new Scene(scroll, 760, 600));
        preview.initModality(Modality.WINDOW_MODAL);
        preview.initOwner(dialogStage);
        com.omniforge.ui.theme.ThemeManager.attach(preview.getScene());
        preview.show();
    }

    private void confirmDelete(String fileName) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除「" + fileName + "」吗？其全部切片将从知识库移除，且不可恢复。",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("删除文档");
        confirm.initOwner(dialogStage);
        // 内建 Alert 的场景 show() 时才创建：挂主题避免暗色下弹出白色系统对话框
        com.omniforge.ui.theme.ThemeManager.attachDialog(confirm);
        confirm.showAndWait().ifPresent(choice -> {
            if (choice == ButtonType.OK) {
                int removed = knowledgeService.removeDocument(fileName);
                refresh();
                Toast.show(dialogStage, "已删除「" + fileName + "」（" + removed + " 个切片）", true);
            }
        });
    }

    private void confirmDeleteCategory(String category, int fileCount) {
        String label = categoryLabel(category);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除分类「" + label + "」下的全部 " + fileCount + " 个文件吗？"
                        + "这些文件的全部切片将从知识库移除，且不可恢复。",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("删除整类");
        confirm.initOwner(dialogStage);
        com.omniforge.ui.theme.ThemeManager.attachDialog(confirm);
        confirm.showAndWait().ifPresent(choice -> {
            if (choice == ButtonType.OK) {
                int removed = knowledgeService.removeByCategory(category);
                collapsedGroups.remove(category);
                refresh();
                Toast.show(dialogStage, "已删除分类「" + label + "」下 " + fileCount
                        + " 个文件（" + removed + " 个切片）", true);
            }
        });
    }

    // ---------- 上传（后台虚拟线程 + 进度显示） ----------

    /** 面板顶/空状态提示文案（与解析能力同步） */
    private static final String SUPPORT_HINT =
            "支持文本（txt/md/csv/json/…）与 Word/Excel/PowerPoint/PDF（docx/doc/xlsx/xls/pptx/ppt）、"
                    + "HTML/RTF；图片与扫描件暂不支持（需 OCR）";

    /** 与 DocumentParsers.defaults() 支持集合对齐（doc 包，2026-09-04 文件格式扩展） */
    private static final List<String> SUPPORTED_EXT = List.of(
            ".txt", ".md", ".markdown", ".log", ".csv", ".tsv", ".json", ".xml",
            ".yml", ".yaml", ".properties", ".ini", ".conf", ".cfg", ".toml", ".sql",
            ".css", ".js", ".ts", ".bat", ".cmd", ".ps1", ".sh",
            ".py", ".java", ".c", ".cpp", ".cc", ".h", ".hpp",
            ".htm", ".html", ".rtf",
            ".pdf", ".docx", ".doc", ".xlsx", ".xls", ".pptx", ".ppt");

    /** 单次上传总量上限（护栏，2026-09-04 设计确认：100 MB） */
    private static final long MAX_UPLOAD_BYTES = 100L * 1024 * 1024;

    /** 选择多个文档一次上传（Ctrl/Shift 多选） */
    private void chooseAndUpload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择文档（可多选）");
        String[] allGlobs = SUPPORTED_EXT.stream().map(ext -> "*" + ext).toArray(String[]::new);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("支持的文档（全部）", allGlobs),
                new FileChooser.ExtensionFilter("Office 办公",
                        "*.docx", "*.doc", "*.xlsx", "*.xls", "*.pptx", "*.ppt"),
                new FileChooser.ExtensionFilter("PDF", "*.pdf"),
                new FileChooser.ExtensionFilter("文本 / 网页 / RTF",
                        "*.txt", "*.md", "*.markdown", "*.log", "*.csv", "*.tsv", "*.json",
                        "*.xml", "*.yml", "*.yaml", "*.properties", "*.htm", "*.html", "*.rtf"),
                new FileChooser.ExtensionFilter("全部文件", "*.*"));
        List<File> files = chooser.showOpenMultipleDialog(dialogStage);
        if (files == null || files.isEmpty()) {
            return;
        }
        // 分类 null → 每个文件按其所在目录自动归类
        uploadPaths(files.stream().map(File::toPath).toList(), null);
    }

    /** 选择文件夹：递归收集其中受支持的文档后批量上传（整文件夹按所选文件夹名归为一类，扁平化） */
    private void chooseAndUploadFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择文件夹（递归收集其中的文档）");
        File dir = chooser.showDialog(dialogStage);
        if (dir == null) {
            return;
        }
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(dir.toPath())) {
            for (Path path : walk.toList()) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String rawName = path.getFileName().toString();
                // Office 打开文件时的残留临时文件（~$ 前缀）不是真实文档，跳过
                if (rawName.startsWith("~$")) {
                    continue;
                }
                String name = rawName.toLowerCase();
                if (SUPPORTED_EXT.stream().anyMatch(name::endsWith)) {
                    found.add(path);
                }
            }
        } catch (Exception e) {
            Toast.show(dialogStage, "读取文件夹失败：" + e.getMessage(), false);
            return;
        }
        if (found.isEmpty()) {
            Toast.show(dialogStage, "文件夹内没有可入库的文档（文本/Office/PDF 等）", false);
            return;
        }
        // 知识库分类：递归收集的文档统一归类到所选文件夹名（子目录不再细分）
        uploadPaths(found, dir.getName());
    }

    /**
     * 批量上传：虚拟线程顺序执行，逐文件进度 + 汇总成功/失败；不因单个失败中断其余。
     *
     * @param category 上传分类（空串 = 未分类；null = 每个文件按其所在目录自动归类）
     */
    private void uploadPaths(List<Path> files, String category) {
        if (files.isEmpty()) {
            return;
        }
        // 护栏：单次上传总量 ≤ 100MB（先计算，超限不入队不占 busy）
        long totalBytes = 0;
        for (Path file : files) {
            try {
                totalBytes += Files.size(file);
            } catch (Exception ignored) {
                // 尺寸读不到按 0 计，交由逐文件解析报错
            }
        }
        if (totalBytes > MAX_UPLOAD_BYTES) {
            Toast.show(dialogStage, "单次上传总量不能超过 100 MB（所选共约 "
                    + Math.max(1, totalBytes / 1024 / 1024) + " MB），请分批上传", false);
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        uploadButton.setDisable(true);
        folderButton.setDisable(true);
        progressRow.setVisible(true);
        progressRow.setManaged(true);
        progressBar.setProgress(0);
        progressLabel.setText("准备上传 " + files.size() + " 个文件…");
        Thread.ofVirtual().name("omniforge-knowledge-upload", 0).start(() -> {
            int ok = 0;
            int failed = 0;
            for (Path file : files) {
                try {
                    knowledgeService.addDocument(file, category, progress -> Platform.runLater(() -> {
                        progressLabel.setText(switch (progress.stage()) {
                            case EMBEDDING -> "向量化 " + progress.fileName() + "："
                                    + progress.doneChunks() + "/" + progress.totalChunks();
                            case DONE -> "挂载完成：" + progress.fileName() + "（"
                                    + progress.totalChunks() + " 个切片）";
                            default -> progress.stage().label() + " " + progress.fileName();
                        });
                        double ratio = progress.ratio();
                        if (ratio >= 0) {
                            progressBar.setProgress(ratio);
                        }
                    }));
                    ok++;
                } catch (Exception e) {
                    failed++;
                    log.warn("文档挂载失败（继续下一个）：{} —— {}", file.getFileName(), e.getMessage());
                }
            }
            int okCount = ok;
            int failCount = failed;
            Platform.runLater(() -> {
                boolean showing = dialogStage != null && dialogStage.isShowing();
                progressLabel.setText("完成：" + okCount + " 成功"
                        + (failCount > 0 ? "，" + failCount + " 失败" : ""));
                // 上传期间对话框可关闭/最小化到主窗：完成后若已关窗则只弹主屏 Toast
                if (showing) {
                    refresh();
                }
                Toast.show(showing ? dialogStage : null, okCount > 0
                        ? "已挂载 " + okCount + " 个文件" + (failCount > 0 ? "，失败 " + failCount : "")
                        : "全部失败（" + failCount + " 个）", failCount == 0);
                uploadButton.setDisable(false);
                folderButton.setDisable(false);
                busy.set(false);
            });
        });
    }
}
