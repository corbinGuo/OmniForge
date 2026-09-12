package com.omniforge.ui;

import com.omniforge.core.eula.EulaService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * EULA 同意书对话框（Phase 4，需求 9.2：首次启动强制签署）。
 * 拒绝即退出应用；同意后经 {@link EulaService} 持久化状态。
 */
final class EulaDialog {

    private EulaDialog() {
    }

    /** 弹出并阻塞至用户选择；返回 true=同意，false=拒绝 */
    static boolean showAndWait(Window owner, EulaService eulaService) {
        TextArea textArea = new TextArea(loadEulaText());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefRowCount(16);
        textArea.getStyleClass().add("config-editor");

        Label title = new Label("最终用户许可协议（首次使用请仔细阅读）");
        title.getStyleClass().add("header");

        final boolean[] result = {false};
        Button acceptButton = new Button("同意并继续");
        acceptButton.getStyleClass().add("primary");
        acceptButton.setOnAction(event -> {
            eulaService.accept();
            result[0] = true;
            ((Stage) acceptButton.getScene().getWindow()).close();
        });
        Button declineButton = new Button("拒绝并退出");
        declineButton.setOnAction(event -> {
            result[0] = false;
            ((Stage) declineButton.getScene().getWindow()).close();
        });

        HBox buttons = new HBox(8, acceptButton, declineButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(10));
        VBox root = new VBox(8, title, textArea, buttons);
        root.setPadding(new Insets(12));

        Stage stage = new Stage();
        stage.setTitle("OmniForge EULA");
        stage.setScene(new Scene(root, 620, 480));
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        com.omniforge.ui.theme.ThemeManager.attach(stage.getScene());
        stage.showAndWait();
        return result[0];
    }

    private static String loadEulaText() {
        try (InputStream in = EulaDialog.class.getResourceAsStream("/eula.txt")) {
            if (in == null) {
                return "（EULA 文本缺失，请确认安装包完整）";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "（EULA 文本读取失败：" + e.getMessage() + "）";
        }
    }
}
