package com.omniforge.ui;

import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * 滑动开关（UI 二轮 P1）：品牌色激活态，替代原生 CheckBox。
 *
 * <p>轨道/滑钮均为 Shape，配色经 CSS（.toggle-switch 与 :on 伪类），
 * 明暗主题与白标品牌色自动跟随；点击整行切换，滑钮 150ms 平滑滑动。</p>
 */
final class ToggleSwitch extends HBox {

    private static final PseudoClass ON = PseudoClass.getPseudoClass("on");

    private final BooleanProperty selected = new SimpleBooleanProperty(this, "selected", false);
    private final Rectangle track;
    private final Circle knob;

    ToggleSwitch(String text) {
        super(8);
        setAlignment(Pos.CENTER_LEFT);
        setCursor(Cursor.HAND);
        getStyleClass().add("toggle-switch");

        track = new Rectangle(36, 20);
        track.setArcWidth(20);
        track.setArcHeight(20);
        track.getStyleClass().add("track");
        knob = new Circle(8);
        knob.getStyleClass().add("knob");
        knob.setTranslateX(-9); // 未激活位置（左侧）
        StackPane pill = new StackPane(track, knob);

        getChildren().addAll(pill, new Label(text));
        setOnMouseClicked(event -> setSelected(!isSelected()));
        selectedProperty().addListener((obs, old, on) -> applyState(on));
    }

    /** 悬停提示（HBox 非 Control，无 setTooltip，用静态安装） */
    ToggleSwitch withTooltip(String text) {
        Tooltip.install(this, new Tooltip(text));
        return this;
    }

    BooleanProperty selectedProperty() {
        return selected;
    }

    boolean isSelected() {
        return selected.get();
    }

    void setSelected(boolean value) {
        selected.set(value);
    }

    private void applyState(boolean on) {
        pseudoClassStateChanged(ON, on);
        TranslateTransition move = new TranslateTransition(Duration.millis(150), knob);
        move.setToX(on ? 9 : -9);
        move.play();
    }
}
