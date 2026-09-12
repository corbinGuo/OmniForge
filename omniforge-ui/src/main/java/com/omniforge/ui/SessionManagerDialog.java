package com.omniforge.ui;

import com.omniforge.ui.enterprise.EnterpriseBridge;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 企业版会话管理对话框（C-tier 批次 4-2）：历史会话列表（时间倒序）+
 * 双击恢复会话 + 重命名/删除 + 新会话。数据与动作经回调由装配层注入
 * （ui 不依赖 app，避免反向依赖）。
 */
public final class SessionManagerDialog {

    /** 恢复会话回调（装配层实现：拉消息并回填主窗口；sessionId=null 表示新会话） */
    @FunctionalInterface
    public interface RestoreAction {
        void restore(String sessionId);
    }

    private SessionManagerDialog() {
    }

    /**
     * 显示会话管理对话框。
     *
     * @param viewerOnly 只读角色（viewer）：隐藏新建/重命名/删除，仅可查看与恢复自己的会话
     * @param sessions   会话列表供应器（每次打开时拉取最新缓存）
     * @param rename     重命名动作（id, 新名）→ 抛异常即 Toast 提示
     * @param delete     删除动作（id）
     * @param restore    恢复动作（id 或 null=新会话）
     */
    public static void show(Stage owner, boolean viewerOnly,
                            java.util.function.Supplier<List<EnterpriseBridge.SessionItem>> sessions,
                            BiConsumer<String, String> rename, Consumer<String> delete,
                            RestoreAction restore) {
        ListView<EnterpriseBridge.SessionItem> listView = new ListView<>();
        listView.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(EnterpriseBridge.SessionItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String time = item.createdAt() == null ? ""
                        : item.createdAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                setText(item.name() + "    （" + time + "）");
            }
        });

        // 搜索 + 分页（企业版 P2）：每页 50 条，按名称过滤
        javafx.scene.control.TextField searchField = new javafx.scene.control.TextField();
        searchField.setPromptText("搜索会话名称…");
        Label pageLabel = new Label();
        pageLabel.getStyleClass().add("status");
        Button prevButton = new Button("◀ 上一页");
        Button nextButton = new Button("下一页 ▶");
        final int[] page = {0};
        final List<EnterpriseBridge.SessionItem>[] all = new List[1];
        final String[] query = {""};

        Runnable applyView = () -> {
            List<EnterpriseBridge.SessionItem> filtered = all[0].stream()
                    .filter(item -> query[0].isBlank()
                            || item.name().toLowerCase().contains(query[0].toLowerCase()))
                    .toList();
            int totalPages = Math.max(1, (filtered.size() + 49) / 50);
            if (page[0] >= totalPages) {
                page[0] = totalPages - 1;
            }
            int from = page[0] * 50;
            int to = Math.min(filtered.size(), from + 50);
            listView.getItems().setAll(filtered.subList(from, to));
            pageLabel.setText("第 " + (page[0] + 1) + "/" + totalPages + " 页 · 共 " + filtered.size() + " 个会话");
            prevButton.setDisable(page[0] <= 0);
            nextButton.setDisable(page[0] >= totalPages - 1);
        };
        searchField.textProperty().addListener((obs, old, text) -> {
            query[0] = text == null ? "" : text.trim();
            page[0] = 0;
            applyView.run();
        });
        prevButton.setOnAction(event -> {
            if (page[0] > 0) {
                page[0]--;
                applyView.run();
            }
        });
        nextButton.setOnAction(event -> {
            page[0]++;
            applyView.run();
        });

        Label hint = new Label(viewerOnly
                ? "只读角色（viewer）：双击会话仅查看与恢复"
                : "双击会话恢复对话 · 右键或按钮重命名/删除");
        hint.getStyleClass().add("status");
        Button renameButton = new Button("重命名");
        Button deleteButton = new Button("删除");
        deleteButton.getStyleClass().add("danger");
        Button newButton = new Button("＋ 新会话");
        Button closeButton = new Button("关闭");
        if (viewerOnly) {
            newButton.setVisible(false);
            newButton.setManaged(false);
            renameButton.setVisible(false);
            renameButton.setManaged(false);
            deleteButton.setVisible(false);
            deleteButton.setManaged(false);
        }
        HBox buttons = new HBox(8, newButton, renameButton, deleteButton,
                new javafx.scene.layout.Region(), pageLabel, prevButton, nextButton, closeButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(buttons.getChildren().get(3), Priority.ALWAYS);
        // 按钮保持完整文字宽度，避免被 HBox 挤缩导致文字裁切（评审反馈）
        for (Button b : List.of(newButton, renameButton, deleteButton, prevButton, nextButton, closeButton)) {
            b.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        }

        Stage stage = new Stage();
        stage.setTitle("会话管理");
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);

        Runnable refresh = () -> {
            all[0] = sessions.get();
            page[0] = 0;
            applyView.run();
        };
        Runnable onRestore = () -> {
            EnterpriseBridge.SessionItem selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                restore.restore(selected.id());
                stage.close();
            }
        };
        listView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                onRestore.run();
            }
        });
        renameButton.setOnAction(event -> {
            EnterpriseBridge.SessionItem selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            TextInputDialog prompt = new TextInputDialog(selected.name());
            prompt.setTitle("重命名会话");
            prompt.setHeaderText("为「" + selected.name() + "」输入新名称");
            prompt.initOwner(stage);
            // 内建 Dialog 的场景 show() 时才创建：挂主题避免暗色下弹出白色系统对话框
            com.omniforge.ui.theme.ThemeManager.attachDialog(prompt);
            Optional<String> name = prompt.showAndWait();
            if (name.isEmpty() || name.get().isBlank()) {
                return;
            }
            try {
                rename.accept(selected.id(), name.get().trim());
                refresh.run();
            } catch (Exception e) {
                Toast.show(null, "重命名失败：" + e.getMessage(), false);
            }
        });
        deleteButton.setOnAction(event -> {
            EnterpriseBridge.SessionItem selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            try {
                delete.accept(selected.id());
                refresh.run();
                Toast.show(null, "会话已删除", true);
            } catch (Exception e) {
                Toast.show(null, "删除失败：" + e.getMessage(), false);
            }
        });
        newButton.setOnAction(event -> {
            restore.restore(null);
            stage.close();
        });
        closeButton.setOnAction(event -> stage.close());

        VBox box = new VBox(8, hint, searchField, listView, buttons);
        box.setPadding(new Insets(12));
        VBox.setVgrow(listView, Priority.ALWAYS);
        stage.setScene(new Scene(box, 760, 540));
        com.omniforge.ui.theme.ThemeManager.attach(stage.getScene());
        refresh.run();
        stage.show();
    }
}
