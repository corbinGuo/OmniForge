package com.omniforge.ui.enterprise;

import com.omniforge.ui.Toast;
import com.omniforge.ui.theme.ThemeManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文档产出（D5）预览小窗（Q4 预览小窗）：只读 Markdown 预览 + 顶部操作行
 * 📋复制 / 💾另存为 .md / ❌关闭。非模态，不遮会话区；保存走虚拟线程 + Toast 完整路径
 * （参照 OpsCenterDialog.exportCsv 的 FileChooser 先例）。
 */
public final class DocDraftDialog {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DocDraftDialog.class);

    private final String markdown;
    private final String defaultFileName;
    private final Stage stage = new Stage();

    private DocDraftDialog(String markdown, String defaultFileName) {
        this.markdown = markdown;
        this.defaultFileName = defaultFileName;
    }

    public static void show(Stage owner, String markdown, String defaultFileName) {
        new DocDraftDialog(markdown, defaultFileName).open(owner);
    }

    private void open(Stage owner) {
        stage.setTitle("📄 文档草稿预览");
        stage.initModality(Modality.NONE);
        stage.initOwner(owner);
        Scene scene = new Scene(buildRoot(), 780, 620);
        stage.setMinWidth(560);
        stage.setMinHeight(400);
        stage.setScene(scene);
        ThemeManager.attach(scene);
        stage.show();
    }

    private javafx.scene.Parent buildRoot() {
        Label title = new Label("📄 文档草稿");
        title.getStyleClass().add("header");

        TextArea preview = new TextArea(markdown);
        preview.setEditable(false);
        preview.setWrapText(false);
        VBox.setVgrow(preview, Priority.ALWAYS);

        Button copyButton = new Button("📋 复制");
        copyButton.setOnAction(e -> copyToClipboard());
        Button saveButton = new Button("💾 另存为 .md");
        saveButton.getStyleClass().add("primary");
        saveButton.setOnAction(e -> saveAs());
        Button closeButton = new Button("❌ 关闭");
        closeButton.setOnAction(e -> stage.close());

        HBox actions = new HBox(8, copyButton, saveButton, closeButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(preview, Priority.ALWAYS);

        VBox root = new VBox(10, title, actions, preview);
        root.setPadding(new Insets(14));
        return root;
    }

    private void copyToClipboard() {
        ClipboardContent content = new ClipboardContent();
        content.putString(markdown);
        Clipboard.getSystemClipboard().setContent(content);
        Toast.show(stage, "已复制到剪贴板", true);
    }

    private void saveAs() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("另存为 Markdown");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Markdown", "*.md"));
        chooser.setInitialFileName(defaultFileName == null || defaultFileName.isBlank()
                ? "doc-draft.md" : defaultFileName);
        java.io.File target = chooser.showSaveDialog(stage);
        if (target == null) {
            return;
        }
        Thread.ofVirtual().name("omniforge-doc-draft-save", 0).start(() -> {
            try {
                Files.writeString(Path.of(target.toURI()), markdown);
                Platform.runLater(() -> Toast.show(stage,
                        "已保存：" + target.getAbsolutePath(), true));
            } catch (IOException ex) {
                log.warn("文档草稿保存失败：{}", ex.getMessage());
                Platform.runLater(() -> Toast.show(stage,
                        "保存失败：" + ex.getMessage(), false));
            }
        });
    }
}
