package com.omniforge.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * 企业版登录对话框（C-tier 批次 4-2）：服务端地址 / 账号 / 密码 +
 * 登录状态反馈（加载中/成功/失败）。登录逻辑经回调由装配层注入
 * （ui 不依赖 app，避免反向依赖）。
 */
public final class EnterpriseLoginDialog {

    /** 登录执行回调（装配层实现：调用 EnterpriseSessionManager.login）；返回服务端角色 */
    @FunctionalInterface
    public interface LoginAction {
        /** 执行登录；失败抛异常（消息展示于对话框） */
        String login(String serverUrl, String username, String password) throws Exception;
    }

    /** 服务端地址保存回调（写 enterprise.yml + 重设管理器地址） */
    @FunctionalInterface
    public interface ServerUrlSaver {
        void save(String serverUrl);
    }

    /** 登录结果：ok = 是否登录成功；role = 服务端返回的角色（企业版 P2 角色感知） */
    public record LoginOutcome(boolean ok, String role) {
    }

    private EnterpriseLoginDialog() {
    }

    /**
     * 显示模态登录框。
     *
     * @return null = 用户取消（调用方应退出应用）；否则为登录结果（ok + 角色）
     */
    public static LoginOutcome showAndWait(Stage owner, String initialServerUrl,
                                           LoginAction loginAction, ServerUrlSaver serverUrlSaver) {
        TextField urlField = new TextField(initialServerUrl);
        urlField.setPromptText("http://server:8080");
        TextField usernameField = new TextField();
        usernameField.setPromptText("账号");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("密码");
        Label status = new Label("请输入企业版服务端地址与账号密码");
        status.getStyleClass().add("status");
        Button loginButton = new Button("登录");
        loginButton.getStyleClass().add("primary");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        Button cancelButton = new Button("取消");
        cancelButton.setMaxWidth(Double.MAX_VALUE);

        java.util.concurrent.atomic.AtomicReference<LoginOutcome> result = new java.util.concurrent.atomic.AtomicReference<>();
        Stage stage = new Stage();
        stage.setTitle("企业版登录");
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(owner);

        loginButton.setOnAction(event -> {
            String url = urlField.getText().trim();
            String username = usernameField.getText().trim();
            String password = passwordField.getText();
            if (url.isEmpty() || username.isEmpty() || password.isEmpty()) {
                setStatus(status, "请填写服务端地址、账号与密码", false);
                return;
            }
            loginButton.setDisable(true);
            urlField.setDisable(true);
            usernameField.setDisable(true);
            passwordField.setDisable(true);
            setStatus(status, "登录中…", true);
            // 登录为阻塞 HTTP：虚拟线程执行，结果回 UI 线程
            Thread.ofVirtual().start(() -> {
                try {
                    String role = loginAction.login(url, username, password);
                    serverUrlSaver.save(url);
                    Platform.runLater(() -> {
                        result.set(new LoginOutcome(true, role));
                        stage.close();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        setStatus(status, "登录失败：" + e.getMessage(), false);
                        loginButton.setDisable(false);
                        urlField.setDisable(false);
                        usernameField.setDisable(false);
                        passwordField.setDisable(false);
                    });
                }
            });
        });
        cancelButton.setOnAction(event -> stage.close());
        passwordField.setOnAction(event -> loginButton.fire());

        Label title = new Label("连接企业版服务端");
        title.getStyleClass().add("empty-title");
        HBox buttons = new HBox(8, cancelButton, loginButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(10, title,
                new Label("服务端地址"), urlField,
                new Label("账号"), usernameField,
                new Label("密码"), passwordField,
                status, buttons);
        box.setPadding(new Insets(20));
        box.setMinWidth(360);
        VBox.setVgrow(loginButton, javafx.scene.layout.Priority.NEVER);

        stage.setScene(new Scene(box, 380, 340));
        com.omniforge.ui.theme.ThemeManager.attach(stage.getScene());
        stage.showAndWait();
        return result.get();
    }

    private static void setStatus(Label status, String text, boolean neutral) {
        status.setText(text);
        status.getStyleClass().removeAll("bubble-error");
        if (!neutral) {
            status.getStyleClass().add("bubble-error");
        }
    }
}
