package com.omniforge.ui;

import com.omniforge.ui.branding.BrandingManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * 关于对话框（C-tier 批次 4-1 白标）：应用名称 / Logo / 版本，
 * 内容来自 branding.yml（默认官方品牌）。
 */
public final class AboutDialog {

    private AboutDialog() {
    }

    /** 以模态窗口显示关于信息 */
    public static void show(Stage owner) {
        VBox box = new VBox(12);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(28));

        Image logo = BrandingManager.logoImage();
        if (logo != null) {
            ImageView logoView = new ImageView(logo);
            logoView.setFitWidth(64);
            logoView.setFitHeight(64);
            logoView.setPreserveRatio(true);
            box.getChildren().add(logoView);
        } else {
            Label glyph = new Label("◆");
            glyph.setStyle("-fx-font-size: 40px; -fx-text-fill: -of-brand;");
            box.getChildren().add(glyph);
        }

        Label name = new Label(BrandingManager.appName());
        name.getStyleClass().add("empty-title");
        Label version = new Label("版本 " + BrandingManager.APP_VERSION);
        version.getStyleClass().add("status");
        Label copyright = new Label("Apache 2.0 · 本地优先的 AI 工作台");
        copyright.getStyleClass().add("status");
        Label contact = new Label("专业版 / 企业版 / 合作：corbin_guo@qq.com");
        contact.getStyleClass().add("status");
        box.getChildren().addAll(name, version, copyright, contact);

        Stage stage = new Stage();
        stage.setTitle("关于 " + BrandingManager.appName());
        stage.setScene(new Scene(box, 340, 260));
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        com.omniforge.ui.theme.ThemeManager.attach(stage.getScene());
        stage.show();
    }
}
