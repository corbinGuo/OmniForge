package com.omniforge.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Toast 通知（UI_DESIGN §7）：右上角浮层，绿成功/红失败，3 秒自动消失。
 */
public final class Toast {

    private Toast() {
    }

    /** 在 owner 窗口右上角弹出通知；owner 为 null 时定位到主屏幕右上角 */
    public static void show(Window owner, String message, boolean success) {
        Platform.runLater(() -> {
            Label label = new Label((success ? "✓ " : "✕ ") + message);
            label.getStyleClass().addAll("toast", success ? "toast-success" : "toast-error");

            Popup popup = new Popup();
            popup.getContent().add(label);
            popup.setAutoHide(true);
            double x;
            double y;
            if (owner != null) {
                x = owner.getX() + owner.getWidth() - 300;
                y = owner.getY() + 48;
                popup.show(owner, x, y);
                // Popup 使用独立 scene，需继承 owner 的样式表（show 后 scene 才创建）
                javafx.scene.Scene popupScene = popup.getScene();
                if (popupScene != null && owner.getScene() != null) {
                    popupScene.getStylesheets().addAll(owner.getScene().getStylesheets());
                }
            } else {
                var bounds = javafx.stage.Screen.getPrimary().getVisualBounds();
                popup.show((Window) null);
                popup.setX(bounds.getMaxX() - 300);
                popup.setY(bounds.getMinY() + 48);
                javafx.scene.Scene popupScene = popup.getScene();
                if (popupScene != null) {
                    com.omniforge.ui.theme.ThemeManager.attach(popupScene);
                }
            }

            PauseTransition pause = new PauseTransition(Duration.seconds(3));
            pause.setOnFinished(event -> popup.hide());
            pause.play();
        });
    }
}
