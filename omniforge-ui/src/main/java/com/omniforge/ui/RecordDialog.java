package com.omniforge.ui;

import com.omniforge.core.persistence.entity.DebateRecord;
import com.omniforge.core.persistence.service.DebateRecordService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 辩论记录中心（2.7）：
 * - 历史列表（时间倒序，双击即重播）
 * - 重播：逐条渐进回放（播放/暂停/从头），模拟原辩论节奏
 * - 导出：Markdown/HTML 写入 <配置目录>/exports/，完成后可直接打开文件夹
 */
final class RecordDialog {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final DebateRecordService recordService;
    private final Path exportsDir;

    RecordDialog(DebateRecordService recordService, Path configDir) {
        this.recordService = recordService;
        this.exportsDir = configDir.resolve("exports");
    }

    void show(Window owner) {
        ListView<DebateRecord> listView = new ListView<>();
        refreshList(listView);

        // 布局：标题 → 操作 → 内容（P1 重排）
        Button replayButton = new Button("▶ 重播");
        replayButton.setOnAction(event -> {
            DebateRecord record = selectedOrToast(listView);
            if (record != null) {
                showReplay(owner, record);
            }
        });
        Button mdButton = new Button("📄 导出 Markdown");
        mdButton.setOnAction(event -> {
            DebateRecord record = selectedOrToast(listView);
            if (record != null) {
                export(record, true);
            }
        });
        Button htmlButton = new Button("🌐 导出 HTML");
        htmlButton.setOnAction(event -> {
            DebateRecord record = selectedOrToast(listView);
            if (record != null) {
                export(record, false);
            }
        });
        Button folderButton = new Button("📂 打开导出目录");
        folderButton.setOnAction(event -> openExportsFolder());
        Button backButton = new Button("← 返回");
        backButton.setOnAction(event -> ((Stage) listView.getScene().getWindow()).close());

        // 布局统一（P0/P1 三修）：标题行（← 返回左上角 + 标题）→ 操作行 → 内容
        Label title = new Label("🗂 辩论记录");
        title.getStyleClass().add("empty-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox titleRow = new HBox(8, backButton, title, spacer);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("选择记录后重播/导出（双击同样可重播）· 导出目录：" + exportsDir.toAbsolutePath());
        hint.getStyleClass().add("status");
        HBox buttons = new HBox(8, replayButton, mdButton, htmlButton, folderButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(4, 0, 8, 0));
        VBox root = new VBox(6, titleRow, hint, buttons, listView);
        root.setPadding(new Insets(12));
        VBox.setVgrow(listView, javafx.scene.layout.Priority.ALWAYS);
        listView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && listView.getSelectionModel().getSelectedItem() != null) {
                showReplay(owner, listView.getSelectionModel().getSelectedItem());
            }
        });

        Stage stage = new Stage();
        stage.setTitle("OmniForge"); // 标题去重：页内已有「🗂 辩论记录」
        stage.setScene(new Scene(root, 680, 500));
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        com.omniforge.ui.theme.ThemeManager.attach(stage.getScene());
        stage.show();
    }

    /** 选中记录；未选中时 Toast 提示并返回 null */
    private DebateRecord selectedOrToast(ListView<DebateRecord> listView) {
        DebateRecord record = listView.getSelectionModel().getSelectedItem();
        if (record == null) {
            Toast.show(null, "请先选择一条记录", false);
        }
        return record;
    }

    private void refreshList(ListView<DebateRecord> listView) {
        List<DebateRecord> records = recordService.listRecords();
        listView.setItems(FXCollections.observableArrayList(records));
        listView.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(DebateRecord record, boolean empty) {
                super.updateItem(record, empty);
                if (empty || record == null) {
                    setText(null);
                } else {
                    setText("[" + TIME_FORMAT.format(record.getStartedAt()) + "] "
                            + record.getTopic() + " ｜ " + record.getStopReason()
                            + (record.getWinnerAlias() == null ? "" : "｜🏆 " + record.getWinnerAlias()));
                }
            }
        });
    }

    /** 历史重播：逐条渐进回放（默认每条约 900ms），可暂停/继续/从头 */
    private void showReplay(Window owner, DebateRecord record) {
        List<DebateRecordService.ReplayItem> items = recordService.replay(record);
        ListView<String> replayList = new ListView<>();
        replayList.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(item);
                setWrapText(true);
            }
        });

        Label status = new Label("重播「" + record.getTopic() + "」 · 共 " + items.size() + " 条");
        status.getStyleClass().add("status");

        Button playButton = new Button("▶ 播放");
        Button pauseButton = new Button("⏸ 暂停");
        Button restartButton = new Button("⏮ 从头");
        Button closeButton = new Button("关闭");

        PauseTransition timer = new PauseTransition(Duration.millis(900));
        final int[] index = {0};
        final boolean[] paused = {false};
        timer.setOnFinished(event -> {
            if (paused[0] || index[0] >= items.size()) {
                return;
            }
            replayList.getItems().add(formatItem(items.get(index[0])));
            replayList.scrollTo(index[0]);
            index[0]++;
            if (index[0] >= items.size()) {
                status.setText("重播完成 · 共 " + items.size() + " 条");
                return;
            }
            timer.playFromStart();
        });

        playButton.setOnAction(event -> {
            paused[0] = false;
            if (index[0] >= items.size()) {
                index[0] = 0;
                replayList.getItems().clear();
                status.setText("重播「" + record.getTopic() + "」 · 共 " + items.size() + " 条");
            }
            timer.playFromStart();
        });
        pauseButton.setOnAction(event -> {
            paused[0] = true;
            timer.stop();
            status.setText("已暂停（第 " + index[0] + "/" + items.size() + " 条）");
        });
        restartButton.setOnAction(event -> {
            paused[0] = true;
            timer.stop();
            index[0] = 0;
            replayList.getItems().clear();
            status.setText("重播「" + record.getTopic() + "」 · 共 " + items.size() + " 条");
        });
        closeButton.setOnAction(event -> {
            timer.stop();
            ((Stage) replayList.getScene().getWindow()).close();
        });

        // 布局统一（P0 三修）：← 返回左上角 + 标题 → 操作 → 内容
        Button backButton = new Button("← 返回");
        backButton.setOnAction(event -> {
            timer.stop();
            ((Stage) replayList.getScene().getWindow()).close();
        });
        Label title = new Label("历史重播");
        title.getStyleClass().add("empty-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox titleRow = new HBox(8, backButton, title, spacer);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        HBox controls = new HBox(8, playButton, pauseButton, restartButton, closeButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(0, 0, 8, 0));
        VBox root = new VBox(6, titleRow, status, controls, replayList);
        root.setPadding(new Insets(12));
        VBox.setVgrow(replayList, javafx.scene.layout.Priority.ALWAYS);

        Stage stage = new Stage();
        stage.setTitle("OmniForge"); // 标题去重：页内已有「历史重播」
        stage.setScene(new Scene(root, 680, 540));
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        com.omniforge.ui.theme.ThemeManager.attach(stage.getScene());
        stage.setOnHidden(event -> timer.stop());
        stage.show();
        // 打开即自动开始回放
        timer.playFromStart();
    }

    private static String formatItem(DebateRecordService.ReplayItem item) {
        return switch (item.role()) {
            case "system" -> item.text();
            case "judge" -> "⚖ 裁判：" + item.text();
            default -> "◆ " + item.alias() + "：\n" + item.text();
        };
    }

    private void export(DebateRecord record, boolean markdown) {
        if (record == null) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                Files.createDirectories(exportsDir);
                String extension = markdown ? "md" : "html";
                String content = markdown ? recordService.exportMarkdown(record)
                        : recordService.exportHtml(record);
                Path target = exportsDir.resolve("debate-" + record.getId().substring(0, 8) + "." + extension);
                Files.writeString(target, content);
                Platform.runLater(() -> Toast.show(null,
                        "已导出：" + target.toAbsolutePath(), true));
            } catch (Exception e) {
                Platform.runLater(() -> Toast.show(null, "导出失败：" + e.getMessage(), false));
            }
        });
    }

    private void openExportsFolder() {
        Thread.ofVirtual().start(() -> {
            try {
                Files.createDirectories(exportsDir);
                java.awt.Desktop.getDesktop().open(exportsDir.toFile());
            } catch (Exception e) {
                Platform.runLater(() -> Toast.show(null, "无法打开目录：" + e.getMessage(), false));
            }
        });
    }
}
