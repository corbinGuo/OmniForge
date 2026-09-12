package com.omniforge.ui;

import com.omniforge.common.spi.ToolSpec;
import com.omniforge.ui.theme.ThemeManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具执行人工确认对话框（HITL，TOOL_CONFIRMATION Q2-A）：
 * 工具名 / 参数摘要（JSON 截断 500 字符）/ ⚠ 安全提示 / [允许] / [拒绝]。
 *
 * <p>线程协议：Agent 弹性线程持 {@link CountDownLatch} 阻塞等待；
 * FX 线程 {@code showAndWait}；ScheduledExecutor 60 秒到点对已打开窗口 close()
 * ——超时计拒绝（Q2-A）。用户点选后 latch 放行。</p>
 */
public final class ToolConfirmationDialog {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ToolConfirmationDialog.class);

    /** 确认超时（秒）：到点自动关闭并按拒绝处理（Q2-A） */
    private static final long TIMEOUT_SECONDS = 60;
    /** 参数摘要截断长度（设计 §3.3） */
    private static final int SUMMARY_MAX = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "omniforge-tool-confirm-timeout");
                thread.setDaemon(true);
                return thread;
            });

    private ToolConfirmationDialog() {
    }

    /**
     * 征求用户确认（阻塞直至用户决定或超时）。
     * <p>必须在非 FX 线程调用（Agent 弹性线程）；内部切 FX 弹窗并等待。</p>
     *
     * @return true = 允许执行；false = 拒绝（含 60s 超时）
     */
    public static boolean ask(Stage owner, ToolSpec spec, Map<String, Object> params) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean allowed = new AtomicBoolean(false);
        AtomicBoolean finished = new AtomicBoolean(false);
        Platform.runLater(() -> show(owner, spec, params, latch, allowed, finished));
        try {
            // 弹窗层 60s 自动关闭，此处略宽等待兜底（防御异常导致不 countDown）
            if (!latch.await(TIMEOUT_SECONDS + 2, TimeUnit.SECONDS)) {
                log.warn("工具确认等待超时（兜底），按拒绝处理：{}", spec.name());
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return allowed.get();
    }

    private static void show(Stage owner, ToolSpec spec, Map<String, Object> params,
                             CountDownLatch latch, AtomicBoolean allowed, AtomicBoolean finished) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("⚠ 工具待确认");
        alert.setHeaderText("是否允许执行工具「" + spec.name() + "」？");
        alert.setContentText("参数摘要：\n" + summarize(params) + "\n\n"
                + "此操作可能修改本机文件或执行系统命令，请在确认内容后选择。");
        ButtonType allow = new ButtonType("允许");
        ButtonType deny = new ButtonType("拒绝", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(allow, deny);
        if (owner != null) {
            alert.initOwner(owner);
        }
        ThemeManager.attachDialog(alert);
        // 60s 超时：到点关闭窗口（超时计拒绝，Q2-A）
        ScheduledFuture<?> timeout = TIMER.schedule(() -> {
            if (!finished.get()) {
                Platform.runLater(() -> {
                    if (alert.isShowing()) {
                        alert.close();
                    }
                });
            }
        }, TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Optional<ButtonType> result;
        try {
            result = alert.showAndWait();
        } finally {
            timeout.cancel(false);
            finished.set(true);
        }
        if (result.isPresent() && result.get() == allow) {
            allowed.set(true);
        }
        latch.countDown();
    }

    /** 参数摘要：JSON 序列化 + 截断 500 字符（设计 §3.3） */
    private static String summarize(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "（无参数）";
        }
        try {
            String json = MAPPER.writeValueAsString(params);
            return json.length() <= SUMMARY_MAX ? json : json.substring(0, SUMMARY_MAX) + "…";
        } catch (Exception e) {
            return String.valueOf(params);
        }
    }
}
