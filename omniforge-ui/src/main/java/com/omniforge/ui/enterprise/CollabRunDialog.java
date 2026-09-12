package com.omniforge.ui.enterprise;

import com.omniforge.ui.Toast;
import com.omniforge.ui.enterprise.EnterpriseBridge.AuthExpiredException;
import com.omniforge.ui.enterprise.EnterpriseBridge.CollabList;
import com.omniforge.ui.enterprise.EnterpriseBridge.CollabRunView;
import com.omniforge.ui.enterprise.EnterpriseBridge.ModelOption;
import com.omniforge.ui.theme.ThemeManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多模型协作闭环对话框（A1-A4）：左侧闭环档案，右侧「新建 + 阶段卡」。
 * 阶段：①讨论 → ②结论 → ③执行(Agent+工具) → ④验收（pass=完成 / revise=重试）。
 * viewer 只读：可看档案/产物/讨论全文，不可推进（服务端 403 兜底）。
 */
public final class CollabRunDialog {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CollabRunDialog.class);

    private final EnterpriseBridge bridge;
    private final boolean viewer;
    private final java.util.function.Consumer<String> publisher;
    private final Stage stage = new Stage();
    private final AtomicBoolean busy = new AtomicBoolean();

    // 档案
    private final ListView<CollabRunView> archiveList = new ListView<>();
    private final Label archiveState = new Label("加载档案…");

    // 新建表单
    private final TextField topicField = new TextField();
    private final ComboBox<String> templateCombo = new ComboBox<>();
    private final ComboBox<String> modeCombo = new ComboBox<>();
    private final TextArea systemTextArea = new TextArea();
    private final ListView<ModelOption> modelList = new ListView<>();
    private final ComboBox<String> judgeCombo = new ComboBox<>();
    private final ComboBox<Integer> roundsCombo = new ComboBox<>();
    private final TextField budgetField = new TextField("0.5");
    /** 需要等高对齐的表单控件（显示后按皮肤自然高度实测统一，不裁字） */
    private final List<javafx.scene.control.Control> formControls = new ArrayList<>();

    // 详情
    private final Label hint = new Label("在左侧档案选择记录，或在上方新建议题开始一条闭环。");
    private final ScrollPane detailScroll = new ScrollPane();
    private final VBox detail = new VBox(8);
    private volatile CollabRunView current;
    private List<ModelOption> allModels = List.of();
    /** 已自动贴入会话的 runId（done 仅提示一次） */
    private volatile String publishedDoneId;

    private CollabRunDialog(EnterpriseBridge bridge, boolean viewer,
                            java.util.function.Consumer<String> publisher) {
        this.bridge = bridge;
        this.viewer = viewer;
        this.publisher = publisher;
    }

    public static void show(Stage owner, EnterpriseBridge bridge, boolean viewer,
                            java.util.function.Consumer<String> publisher) {
        new CollabRunDialog(bridge, viewer, publisher).open(owner);
    }

    private void open(Stage owner) {
        stage.setTitle("🎤 多模型协作（闭环：讨论 → 结论 → 执行 → 验收）");
        // 非模态：不遮住会话区，跑动期间仍可看/操作会话
        stage.initModality(Modality.NONE);
        stage.initOwner(owner);
        Scene scene = new Scene(buildRoot(), 1360, 880);
        stage.setMinWidth(1080);
        stage.setMinHeight(720);
        stage.setScene(scene);
        ThemeManager.attach(scene);
        stage.show();
        // 待 CSS/皮肤就绪后按实测自然高度统一表单控件（固定像素曾裁字，实测不裁）
        Platform.runLater(() -> Platform.runLater(this::alignFormControlHeights));
        loadModels();
        loadArchive();
    }

    /** 表单控件等高：取各皮肤自然 prefHeight 最大值统一；不设 maxHeight 上限，绝无裁字 */
    private void alignFormControlHeights() {
        double max = 0;
        for (javafx.scene.control.Control c : formControls) {
            double h = c.prefHeight(-1);
            if (h > 0 && !Double.isNaN(h)) {
                max = Math.max(max, h);
            }
        }
        if (max <= 0) {
            return;
        }
        for (javafx.scene.control.Control c : formControls) {
            c.setMinHeight(max);
            c.setPrefHeight(max);
        }
    }

    private javafx.scene.Parent buildRoot() {
        archiveList.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            private final Label text = new Label();
            {
                // 单行完整可见：超出以…收尾，不再垂直裁切
                text.setMaxWidth(Double.MAX_VALUE);
                text.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            }

            @Override
            protected void updateItem(CollabRunView run, boolean empty) {
                super.updateItem(run, empty);
                if (empty || run == null) {
                    setGraphic(null);
                    setTooltip(null);
                } else {
                    String full = run.topic() == null ? "" : run.topic();
                    text.setText(statusIcon(run.status()) + " " + oneLine(full, 80)
                            + "  · " + run.mode() + " · " + run.updatedAt());
                    setGraphic(text);
                    setTooltip(new javafx.scene.control.Tooltip(
                            statusText(run.status()) + "\n" + full
                                    + "\n参与：" + String.join(" + ", run.aliases())
                                    + "\n创建：" + run.createdBy() + " @ " + run.createdAt()));
                }
            }
        });
        archiveList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, run) -> {
            if (run != null) {
                openDetail(run.id());
            }
        });
        archiveList.setFixedCellSize(34);
        Button refreshBtn = new Button("刷新");
        refreshBtn.setOnAction(e -> loadArchive());
        Button deleteSelBtn = new Button("删除选中档案");
        deleteSelBtn.getStyleClass().add("danger");
        deleteSelBtn.setDisable(viewer);
        deleteSelBtn.setOnAction(e -> {
            CollabRunView run = archiveList.getSelectionModel().getSelectedItem();
            if (run == null) {
                Toast.show(null, "请先在左侧选中要删除的档案", false);
                return;
            }
            deleteCurrent(run);
        });
        Label leftTip = new Label(viewer ? "只读：可查看档案与产物"
                : "选中档案后可删除，或在其详情中继续推进");
        leftTip.getStyleClass().add("muted");
        VBox left = new VBox(6, new HBox(6, refreshBtn), archiveState, archiveList,
                deleteSelBtn, leftTip);
        VBox.setVgrow(archiveList, Priority.ALWAYS);

        hint.getStyleClass().add("muted");
        detail.setPadding(new Insets(2));
        detailScroll.setContent(detail);
        detailScroll.setFitToWidth(true);
        detailScroll.setPrefHeight(560);

        Button publishBtn = new Button("📋 贴入会话（像单机版一样把结果写进对话）");
        publishBtn.getStyleClass().add("primary");
        publishBtn.setOnAction(e -> publishCurrent());

        VBox right = new VBox(8, publishBtn, buildForm(),
                new Label("闭环档案 / 运行详情"), hint, detailScroll);
        VBox.setVgrow(detailScroll, Priority.ALWAYS);
        javafx.scene.control.ScrollPane rightScroll = new javafx.scene.control.ScrollPane(right);
        rightScroll.setFitToWidth(true);
        rightScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(detailScroll, Priority.ALWAYS);

        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(left, rightScroll);
        split.setDividerPositions(0.24);
        return split;
    }

    private VBox buildForm() {
        templateCombo.getItems().addAll(
                "圆桌讨论（推荐）", "正反辩论", "发散头脑风暴",
                "方案评审（推荐+质询）", "一致性收敛", "自由（自定义）");
        templateCombo.setValue("圆桌讨论（推荐）");
        templateCombo.setOnAction(e -> applyTemplate(templateCombo.getValue()));
        modeCombo.getItems().addAll("debate", "discussion", "brainstorm");
        modeCombo.setValue("discussion");
        // 模式下拉以中文展示（值仍为英文键，模板/接口不变）
        modeCombo.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String key, boolean empty) {
                super.updateItem(key, empty);
                setText(empty || key == null ? null : modeLabel(key));
                setTooltip(empty || key == null ? null : new javafx.scene.control.Tooltip(modeExplain(key)));
            }
        });
        modeCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String key, boolean empty) {
                super.updateItem(key, empty);
                setText(empty || key == null ? null : modeLabel(key));
            }
        });
        Label modeHelp = new Label("模式说明：辩论 = 正反对抗、逐轮质询、可加裁判裁决；"
                + "讨论 = 多角度圆桌、求同存异收敛；头脑风暴 = 发散收集创意再评估。"
                + "建议直接用上方「模板」选择，会自动设好模式与角色指令。");
        modeHelp.setWrapText(true);
        modeHelp.getStyleClass().add("muted");
        roundsCombo.getItems().addAll(1, 2, 3, 5, 8);
        roundsCombo.setValue(3);
        modelList.setPrefHeight(180);
        modelList.getSelectionModel().setSelectionMode(
                javafx.scene.control.SelectionMode.MULTIPLE);
        modelList.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ModelOption option, boolean empty) {
                super.updateItem(option, empty);
                setText(empty || option == null ? null
                        : option.alias() + (option.providerName() == null ? ""
                        : "（" + option.providerName() + "）"));
            }
        });
        Label modelsHint = new Label();
        modelsHint.getStyleClass().add("muted");
        modelList.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener.Change<? extends ModelOption> change) ->
                        modelsHint.setText("已选 " + modelList.getSelectionModel().getSelectedItems().size()
                                + " 个："
                                + modelList.getSelectionModel().getSelectedItems().stream()
                                .map(ModelOption::alias).reduce((a, b) -> a + ", " + b).orElse("—")));
        topicField.setPromptText("议题（例如：为结算平台设计月度对账自动化并给出推荐）");
        judgeCombo.setPromptText("裁判 = 空");
        systemTextArea.setPromptText("角色指令/背景规则（可编辑，随模板填入；注入每名参与模型）");
        systemTextArea.setPrefRowCount(2);
        systemTextArea.setWrapText(true);

        Button startBtn = new Button("▶ ① 开始讨论（创建闭环）");
        startBtn.getStyleClass().add("primary");
        startBtn.setOnAction(e -> createRun());

        HBox cfg = new HBox(10, field("模式", modeCombo), field("轮次", roundsCombo),
                field("预算$", budgetField), field("裁判", judgeCombo));
        // 不强钳固定高度（皮肤自然需求各异，固定值会裁字）；改为显示后实测统一
        // （alignFormControlHeights）：每控件高度 ≥ 自身自然高度 → 文字永不裁剪且同行等高。
        // 表单顶组合框给足最小宽度，长中文（如“方案评审（推荐+质询）”）完整显示不省略。
        for (javafx.scene.control.ComboBox<?> c : new javafx.scene.control.ComboBox<?>[]{
                templateCombo, modeCombo, judgeCombo}) {
            c.setMinWidth(210);
        }
        formControls.add(topicField);
        formControls.add(templateCombo);
        formControls.add(modeCombo);
        formControls.add(roundsCombo);
        formControls.add(budgetField);
        formControls.add(judgeCombo);
        formControls.add(startBtn);
        VBox form = new VBox(6,
                new Label("新建闭环议题" + (viewer ? "（只读角色不可发起）" : "")),
                topicField,
                new Label("模板（自动设模式 + 角色指令）"), templateCombo,
                systemTextArea,
                new Label("参与模型（Ctrl/Shift 多选，≥2）"), modelList, modelsHint,
                cfg, modeHelp,
                new HBox(10, startBtn));
        form.setStyle("-fx-border-color: derive(-fx-color, 20%); -fx-border-width: 1;"
                + " -fx-background-radius: 6; -fx-padding: 8;");
        if (viewer) {
            for (var node : new javafx.scene.Node[]{topicField, templateCombo, systemTextArea,
                    modelList, modeCombo, roundsCombo, budgetField, judgeCombo, startBtn}) {
                node.setDisable(true);
            }
        }
        return form;
    }

    private static String modeLabel(String key) {
        return switch (key == null ? "" : key) {
            case "debate" -> "辩论（对抗质询）";
            case "brainstorm" -> "头脑风暴（发散收集）";
            default -> "讨论（圆桌收敛）";
        };
    }

    private static String modeExplain(String key) {
        return switch (key == null ? "" : key) {
            case "debate" -> "辩论：参与模型分正反立场逐轮论证并互相质询，可设裁判判定获胜方/结论";
            case "brainstorm" -> "头脑风暴：先充分发散收集尽可能多的观点/候选（含大胆想法），再各自评估可行性";
            default -> "讨论：各模型多角度补充与分析，求同存异收敛出结论与行动项（推荐）";
        };
    }

    /** 模板 → 模式 + 角色指令；"自由"不改动 */
    private void applyTemplate(String template) {
        if (template == null || template.startsWith("自由")) {
            return;
        }
        switch (template) {
            case "正反辩论" -> {
                modeCombo.setValue("debate");
                systemTextArea.setText("你是一场正反辩论中的一方：正方主张采纳/自研，反方主张反对/外购，"
                        + "每轮给出一条论据并直接反驳对方上轮观点，语言精炼、给出理由与权衡。");
            }
            case "发散头脑风暴" -> {
                modeCombo.setValue("brainstorm");
                systemTextArea.setText("你是头脑风暴成员：先充分发散产出尽可能多且不同的候选/观点（含大胆想法），"
                        + "不要互相批评；最后每人给出对可行性的简短评估。");
            }
            case "方案评审（推荐+质询）" -> {
                modeCombo.setValue("discussion");
                systemTextArea.setText("你是方案评审专家：先给出你推荐的方案与理由，然后对其它方案提出具体的风险/"
                        + "成本/合规质疑，要求对方回应；目标是通过质询收敛到可执行建议与行动项。");
            }
            case "一致性收敛" -> {
                modeCombo.setValue("discussion");
                systemTextArea.setText("你们的目标是收敛出团队一致结论：先亮明观点与分歧点，再逐步求同存异、"
                        + "合并意见，减少重复；分歧无法消除时明确标注并给出默认建议。");
            }
            default -> { // 圆桌讨论
                modeCombo.setValue("discussion");
                systemTextArea.setText("你是圆桌讨论成员：围绕议题从不同角度补充信息与分析，指出他人盲点，"
                        + "协助形成结论与行动项，避免无意义重复。");
            }
        }
    }

    private static VBox field(String label, javafx.scene.Node node) {
        VBox box = new VBox(3, new Label(label), node);
        return box;
    }

    private void loadModels() {
        Thread.ofVirtual().start(() -> {
            try {
                List<ModelOption> models = bridge.models();
                Platform.runLater(() -> {
                    allModels = models;
                    modelList.getItems().setAll(models);
                    judgeCombo.getItems().clear();
                    judgeCombo.getItems().add("（无裁判）");
                    for (ModelOption m : models) {
                        judgeCombo.getItems().add(m.alias());
                    }
                });
            } catch (Exception e) {
                log.warn("模型清单加载失败：{}", e.getMessage());
            }
        });
    }

    private void createRun() {
        if (viewer || busy.get()) {
            return;
        }
        String topic = topicField.getText();
        if (topic == null || topic.isBlank()) {
            Toast.show(null, "请输入议题", false);
            return;
        }
        List<String> aliases = modelList.getSelectionModel().getSelectedItems().stream()
                .map(ModelOption::alias).distinct().toList();
        if (aliases.size() < 2) {
            Toast.show(null, "请至少勾选 2 个参与模型", false);
            return;
        }
        String judge = judgeCombo.getValue();
        final String judgeAlias = judge == null || judge.startsWith("（") ? null : judge;
        final Integer rounds = roundsCombo.getValue() == null ? 3 : roundsCombo.getValue();
        final Double budget = parseBudget();
        final String systemText = systemTextArea.getText() == null ? null
                : systemTextArea.getText().trim();
        stageBusy(true, "讨论进行中…（约 20-60 秒）");
        Thread.ofVirtual().start(() -> {
            try {
                CollabRunView run = bridge.collabCreate(topic, aliases, judgeAlias,
                        modeCombo.getValue(), rounds, budget,
                        systemText == null || systemText.isEmpty() ? null : systemText,
                        null); // D5 Q6：独立窗口发起无会话上下文，不关联会话
                Platform.runLater(() -> {
                    stageBusy(false, "");
                    refreshCurrent(run);
                    loadArchive();
                    Toast.show(null, "讨论完成，请点「② 生成结论」", true);
                });
            } catch (AuthExpiredException e) {
                Platform.runLater(this::authExpired);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    stageBusy(false, "");
                    Toast.show(null, "启动失败：" + e.getMessage(), false);
                });
            }
        });
    }

    private Double parseBudget() {
        try {
            double v = Double.parseDouble(budgetField.getText().trim());
            return v > 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 详情/阶段卡 ----------

    private void stageBusy(boolean on, String text) {
        if (hint.getText().isEmpty() && text != null && !text.isEmpty()) {
            hint.setText(text);
        }
        if (!text.isEmpty()) {
            hint.setText(text);
        }
        busy.set(on);
    }

    private void openDetail(String runId) {
        Thread.ofVirtual().start(() -> {
            try {
                CollabRunView run = bridge.collabDetail(runId);
                String transcript = bridge.collabTranscript(runId);
                Platform.runLater(() -> refreshCurrent(run, transcript));
            } catch (AuthExpiredException e) {
                Platform.runLater(this::authExpired);
            } catch (Exception e) {
                Platform.runLater(() -> Toast.show(null, "加载失败：" + e.getMessage(), false));
            }
        });
    }

    private void refreshCurrent(CollabRunView run) {
        Thread.ofVirtual().start(() -> {
            try {
                String transcript = bridge.collabTranscript(run.id());
                Platform.runLater(() -> refreshCurrent(run, transcript));
            } catch (Exception e) {
                Platform.runLater(() -> refreshCurrent(run, "（讨论记录读取失败）"));
            }
        });
    }

    private void refreshCurrent(CollabRunView run, String transcript) {
        current = run;
        hint.setText("");
        detail.getChildren().setAll(renderCards(run, transcript));
        // 每次产物更新后回到顶部，讨论/结论紧跟可见，不用来回翻
        detailScroll.setVvalue(0);
    }

    /** 把当前闭环摘要写进主会话对话流（像单机版协作一样可见） */
    private void publishCurrent() {
        CollabRunView run = current;
        if (run == null) {
            Toast.show(null, "请先在左侧选择要贴入会话的档案", false);
            return;
        }
        publishToConversation(run);
    }

    private void maybeAutoPublishDone(CollabRunView run) {
        if (publisher != null && "done".equals(run.status())
                && !run.id().equals(publishedDoneId)) {
            publishedDoneId = run.id();
            publishToConversation(run);
        }
    }

    private void publishToConversation(CollabRunView run) {
        if (publisher == null) {
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append("🎤 协作闭环 · ").append(modeLabel(run.mode()))
                .append(" · ").append(statusText(run.status())).append('\n');
        out.append("议题：").append(run.topic()).append('\n');
        out.append("参与：").append(String.join(" + ", run.aliases()))
                .append(run.judgeAlias() == null ? "" : " · 裁判：" + run.judgeAlias()).append('\n');
        if (run.conclusion() != null && !run.conclusion().isBlank()) {
            out.append("结论：").append(oneLine(run.conclusion(), 500)).append('\n');
        }
        if (run.executionOutput() != null && !run.executionOutput().isBlank()) {
            out.append("执行结果：").append(oneLine(run.executionOutput(), 500)).append('\n');
        }
        if (run.reviewVerdict() != null) {
            out.append("验收：").append(run.reviewVerdict().contains("pass") ? "✅ 通过" : "⚠ 未通过")
                    .append("  ").append(oneLine(run.reviewReason(), 300));
        }
        String text = out.toString().strip();
        try {
            publisher.accept(text);
            Toast.show(null, "已贴入会话对话流", true);
        } catch (Exception e) {
            Toast.show(null, "贴入会话失败：" + e.getMessage(), false);
        }
    }

    private List<javafx.scene.Node> renderCards(CollabRunView run, String transcript) {
        List<javafx.scene.Node> cards = new ArrayList<>();
        cards.add(sectionHeader(run, transcript));

        // ① 讨论
        TextArea discussion = readOnlyArea(transcript == null ? "" : transcript, "讨论全文…");
        Button transcriptBtn = new Button("查看讨论全文");
        transcriptBtn.setOnAction(e -> showTranscript(run, discussion.getText()));
        cards.add(card("① 讨论（debate/discussion/brainstorm）", new HBox(8, transcriptBtn),
                discussion));

        // ② 结论
        TextArea conclusion = readOnlyArea(run.conclusion() == null ? "" : run.conclusion(),
                "尚未生成结论");
        Button concludeBtn = new Button("② 生成结论");
        concludeBtn.setOnAction(e -> advance("生成结论", () -> bridge.collabConclude(run.id())));
        boolean canConclude = run.status().equals("concluding");
        concludeBtn.setDisable(viewer || !canConclude);
        cards.add(card("② 结论（裁判/总结模型）", new HBox(8, concludeBtn), conclusion));

        // ③ 执行
        TextArea task = readOnlyArea(run.executionTask() == null ? "" : run.executionTask(),
                "任务（留空默认 = 结论）");
        task.setEditable(true);
        ComboBox<String> executor = modelCombo(run, run.executorAlias(), run.judgeAlias(), true);
        Button executeBtn = new Button(run.status().equals("revised") ? "③ 重跑执行" : "③ 执行（Agent+工具）");
        executeBtn.setOnAction(e -> advance("执行",
                () -> bridge.collabExecute(run.id(), task.getText(), executor.getValue())));
        boolean canExecute = run.status().equals("concluding") || run.status().equals("revised");
        executeBtn.setDisable(viewer || !canExecute);
        TextArea result = readOnlyArea(run.executionOutput() == null ? "" : run.executionOutput(),
                "尚未执行");
        cards.add(card("③ 执行（可改任务；选执行模型）",
                new HBox(8, executeBtn, new Label("执行模型"), executor), task, result));

        // ④ 验收
        ComboBox<String> reviewer = modelCombo(run, run.judgeAlias(), run.reviewerAlias(), false);
        Button reviewBtn = new Button("④ 验收");
        reviewBtn.setOnAction(e -> advance("验收", () -> bridge.collabReview(run.id(), reviewer.getValue())));
        boolean canReview = run.status().equals("reviewing");
        reviewBtn.setDisable(viewer || !canReview);
        Label verdict = new Label();
        verdict.setWrapText(true);
        if (run.reviewVerdict() != null) {
            verdict.setText((run.reviewVerdict().contains("pass") ? "✅ 验收通过 → 完成"
                    : "⚠ 验收未通过（revised）")
                    + "\n" + (run.reviewReason() == null ? "" : run.reviewReason()));
        }
        Button retryBtn = new Button("↻ 重试执行");
        retryBtn.setOnAction(e -> advance("重试执行",
                () -> bridge.collabRetry(run.id(), task.getText(), executor.getValue())));
        retryBtn.setVisible(run.status().equals("revised"));
        cards.add(card("④ 验收（对照结论检查执行结果）",
                new HBox(8, reviewBtn, new Label("验收模型"), reviewer, retryBtn), verdict));

        // 底部：删除
        Button deleteBtn = new Button("删除记录");
        deleteBtn.setDisable(viewer);
        deleteBtn.setOnAction(e -> deleteCurrent(run));
        HBox footer = new HBox(6, deleteBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        cards.add(footer);
        return cards;
    }

    private javafx.scene.Node sectionHeader(CollabRunView run, String transcript) {
        VBox header = new VBox(3,
                new Label(statusIcon(run.status()) + " " + run.topic()),
                new Label(run.mode() + " · " + String.join(" + ", run.aliases())
                        + (run.judgeAlias() == null ? "" : " · 裁判:" + run.judgeAlias())
                        + " · 创建 " + run.createdBy() + " @ " + run.createdAt()
                        + " · 更新 " + run.updatedAt()));
        header.getChildren().get(0).getStyleClass().add("nav-title");
        header.getChildren().get(1).getStyleClass().add("muted");
        Label status = new Label("状态：" + statusText(run.status()));
        status.getStyleClass().add("status");
        header.getChildren().add(status);
        return header;
    }

    private ComboBox<String> modelCombo(CollabRunView run, String prefer, String fallback,
                                        boolean includeAll) {
        List<String> candidates = new ArrayList<>();
        if (run.aliases() != null) {
            candidates.addAll(run.aliases());
        }
        if (includeAll) {
            for (ModelOption m : allModels) {
                if (!candidates.contains(m.alias())) {
                    candidates.add(m.alias());
                }
            }
        }
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll(candidates);
        String value = prefer != null && candidates.contains(prefer) ? prefer
                : (fallback != null && candidates.contains(fallback) ? fallback
                : (candidates.isEmpty() ? null : candidates.get(candidates.size() - 1)));
        combo.setValue(value);
        return combo;
    }

    private static TextArea readOnlyArea(String text, String prompt) {
        TextArea area = new TextArea(text);
        area.setPromptText(prompt);
        area.setWrapText(true);
        area.setEditable(false);
        return area;
    }

    private static VBox card(String title, javafx.scene.Node... rows) {
        VBox box = new VBox(5);
        box.getChildren().add(new Label(title));
        box.getChildren().addAll(rows);
        box.setStyle("-fx-border-color: derive(-fx-color, 20%); -fx-border-width: 1;"
                + " -fx-background-radius: 6; -fx-padding: 8;");
        return box;
    }

    private void advance(String what, CollabCall call) {
        if (viewer || busy.get() || current == null) {
            return;
        }
        busy.set(true);
        hint.setText(what + "中…（≤5 分钟）");
        Thread.ofVirtual().start(() -> {
            try {
                CollabRunView run = call.run();
                String transcript = bridge.collabTranscript(run.id());
                Platform.runLater(() -> {
                    busy.set(false);
                    hint.setText("");
                    refreshCurrent(run, transcript);
                    loadArchive();
                    Toast.show(null, what + "完成（" + statusText(run.status()) + "）", true);
                    maybeAutoPublishDone(run);
                });
            } catch (AuthExpiredException e) {
                Platform.runLater(this::authExpired);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    busy.set(false);
                    hint.setText(what + "失败");
                    Toast.show(null, what + "失败：" + e.getMessage(), false);
                    if (current != null) {
                        openDetail(current.id());
                    }
                });
            }
        });
    }

    private void deleteCurrent(CollabRunView run) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "删除闭环记录「" + oneLine(run.topic(), 30) + "」？", ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle("确认");
        alert.initOwner(stage);
        ThemeManager.attachDialog(alert);
        if (!alert.showAndWait().map(ButtonType.OK::equals).orElse(false)) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                bridge.collabDelete(run.id());
                Platform.runLater(() -> {
                    current = null;
                    hint.setText("记录已删除");
                    detail.getChildren().clear();
                    archiveList.getSelectionModel().clearSelection();
                    loadArchive();
                });
            } catch (Exception e) {
                Platform.runLater(() -> Toast.show(null, "删除失败：" + e.getMessage(), false));
            }
        });
    }

    private void loadArchive() {
        Thread.ofVirtual().start(() -> {
            try {
                CollabList list = bridge.collabRuns(0, 100);
                Platform.runLater(() -> {
                    archiveState.setText("共 " + list.total() + " 条");
                    archiveList.getItems().setAll(list.items());
                });
            } catch (AuthExpiredException e) {
                Platform.runLater(this::authExpired);
            } catch (Exception e) {
                Platform.runLater(() -> archiveState.setText("档案加载失败：" + e.getMessage()));
            }
        });
    }

    private void showTranscript(CollabRunView run, String text) {
        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("讨论全文：" + oneLine(run.topic(), 30));
        dialog.initOwner(stage);
        TextArea area = readOnlyArea(text, "");
        area.setStyle("-fx-font-family: 'Consolas','Microsoft YaHei',monospace; -fx-font-size: 13px;");
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().setPrefSize(900, 600);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ThemeManager.attachDialog(dialog);
        dialog.show();
    }

    private void authExpired() {
        stage.close();
        Toast.show(null, "登录已过期，请重新登录", false);
    }

    private static String statusIcon(String status) {
        return switch (status == null ? "" : status) {
            case "done" -> "✅";
            case "revised" -> "⚠";
            case "aborted" -> "⛔";
            case "executing", "reviewing", "concluding" -> "🔄";
            default -> "⏸";
        };
    }

    private static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "discussing" -> "讨论中";
            case "concluding" -> "待生成结论（讨论已完成）";
            case "executing" -> "执行中";
            case "reviewing" -> "待验收（执行已完成）";
            case "done" -> "已完成（验收通过）";
            case "revised" -> "验收未通过，可重试";
            case "aborted" -> "中止/失败";
            default -> status == null ? "" : status;
        };
    }

    private static String oneLine(String text, int max) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    @FunctionalInterface
    private interface CollabCall {
        CollabRunView run() throws Exception;
    }
}
