package com.omniforge.ui;

import com.omniforge.core.agent.AgentEngine;
import com.omniforge.core.agent.AgentEvent;
import com.omniforge.core.agent.AgentRunRequest;
import com.omniforge.core.agent.ToolRegistry;
import com.omniforge.core.context.ContextManager;
import com.omniforge.core.context.ContextRole;
import com.omniforge.core.eula.EulaService;
import com.omniforge.knowledge.KnowledgeService;
import com.omniforge.core.persistence.service.LicenseService;
import com.omniforge.core.debate.DebateEngine;
import com.omniforge.core.debate.DebateEvent;
import com.omniforge.core.debate.DebateRequest;
import com.omniforge.core.gateway.GatewayHistoryMessage;
import com.omniforge.core.gateway.GatewayHistoryRole;
import com.omniforge.core.gateway.GatewayProperties;
import com.omniforge.core.gateway.GatewayRequest;
import com.omniforge.core.gateway.GatewayUsage;
import com.omniforge.core.gateway.ModelGateway;
import com.omniforge.core.persistence.service.DebateRecordService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.springframework.context.ApplicationContext;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JavaFX 主窗口（Phase 2）：
 * 导航栏、气泡对话（Enter 发送/Shift+Enter 换行）、Agent 模式（步骤卡片）、
 * 辩论模式（多模型多列流式渲染 + 停止按钮）、空状态。
 */
public class OmniForgeApplication extends Application {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OmniForgeApplication.class);

    /** 当前应用实例（app 装配层的 ToolApproval 通道经静态入口调用确认框；start() 后可用） */
    private static volatile OmniForgeApplication instance;

    private static final double BUBBLE_MAX_WIDTH = 640;
    private static final int TEXT_TRUNCATE = 160;

    /** 主窗口会话键（直连与 Agent 模式共享同一会话上下文，用户确认设计 #4） */
    private static final String UI_SESSION_ID = "ui:default";

    /** 智能路由下拉选项（Pro 专属：选中后 alias=null，由 ModelRouter 按复杂度选模型） */
    private static final String AUTO_ROUTE_LABEL = "🤖 自动路由（Pro）";

    private ModelGateway modelGateway;
    private GatewayProperties gatewayProperties;
    private AgentEngine agentEngine;
    private ToolRegistry toolRegistry;
    private DebateEngine debateEngine;
    private DebateRecordService debateRecordService;
    private EulaService eulaService;
    private LicenseService licenseService;
    private ContextManager contextManager;
    private KnowledgeService knowledgeService;
    /** 插件市场桥接（P1-3：app 装配层注入；null 时隐藏「🧩 插件」入口） */
    private com.omniforge.ui.plugin.PluginBridge pluginBridge;
    /** 企业版桥接（--mode=enterprise 装配时注入；null = 单机模式） */
    private com.omniforge.ui.enterprise.EnterpriseBridge enterpriseBridge;
    /** 企业版当前会话（null = 新会话，发送后由服务端分配） */
    private String enterpriseSessionId;
    /** 企业版当前登录角色（P2-2：admin 显示「🏛 管理」入口） */
    private String enterpriseRole = "";
    /** 「🏛 管理」入口（P2-2：企业模式 + role=admin 时可见，服务端仍强校验） */
    private Button adminButton;
    /** 「🚪 退出登录」（企业模式：清登录态并重出登录框） */
    private Button logoutButton;
    /** 企业版左栏会话列表（布局重构评审：会话常驻左侧，替代「会话」弹窗） */
    private javafx.scene.control.ListView<com.omniforge.ui.enterprise.EnterpriseBridge.SessionItem> enterpriseSessionsList;
    private Button enterpriseNewButton;
    private Button enterpriseRenameButton;
    private Button enterpriseDeleteButton;
    /** 「🧠 多模型」入口（对话优先：在输入框旁开协作条，结果回到会话） */
    private Button enterpriseDebateButton;
    /** 协作发送条（模板/参与模型多选/裁判/轮次）与档案入口（对话优先 D1-D4） */
    private final javafx.scene.control.ComboBox<String> collabTemplate = new javafx.scene.control.ComboBox<>();
    /** 参与模型多选：下拉触发器 + 弹出勾选面板（CheckComboBox 风格；模型再多也不占对话区） */
    private final Button collabModelsPicker = new Button("参与模型 ▾");
    private final javafx.stage.Popup collabModelsPopup = new javafx.stage.Popup();
    private final VBox collabModelsPanel = new VBox(2);
    private final java.util.LinkedHashMap<String, javafx.scene.control.CheckBox> collabModelChecks = new java.util.LinkedHashMap<>();
    private final javafx.scene.control.ComboBox<String> collabJudge = new javafx.scene.control.ComboBox<>();
    /** 轮次：数字输入框（服务端钳制 1~10；缺省按 1 次） */
    private final javafx.scene.control.TextField collabRounds = new javafx.scene.control.TextField("1");
    private final VBox collabComposer = new VBox(6);
    private final Button collabArchiveButton = new Button("🗂 档案");
    /** 「📄 草稿」（D5 文档产出 Q2：collabLine 末尾，与档案并列；写角色可用） */
    private final Button collabDraftButton = new Button("📄 草稿");
    /** B1（U3）：协作条「自动执行」开关——关=②后暂停待用户续跑 */
    private final ToggleSwitch autoExecToggle = new ToggleSwitch("自动执行");
    /** viewer 兜底导出入口（Q7：viewer 只读可见可用；写角色用 collabLine 内按钮） */
    private final Button inputDraftButton = new Button("📄 草稿");
    private boolean collabComposerOn;
    private boolean collabModelsLoaded;
    /** 功能按钮（统一可见性规则表 applyFeatureVisibility 引用；原是 start 局部提升为字段） */
    private Button knowledgeButton;
    private Button recordsButton;
    private Button pluginButton;
    private Button clearContextButton;
    /** 左栏折叠菜单各分组容器（导航①：展开/角色变化时隐藏空分组） */
    private List<VBox> menuGroups = new ArrayList<>();

    private VBox chatBox;
    private ScrollPane chatScroll;

    /** 历史会话侧栏（问题一：可折叠，默认展开） */
    private VBox historySidebar;
    private javafx.scene.control.TreeView<HistoryEntry> historyTree;
    private javafx.scene.control.TextField historySearchField;
    private com.omniforge.core.persistence.service.ChatSessionService chatSessionService;
    /** 当前普通对话会话 ID（null = 新会话，首轮回复完成后自动创建） */
    private String currentChatSessionId;

    private TextArea inputArea;
    /** D7：@ 提及自动补全弹层（企业模式；输入 @ 弹出模型候选，点击插入） */
    private javafx.stage.Popup mentionPopup;
    private javafx.scene.layout.VBox mentionListBox;
    private ComboBox<ModelChoice> modelCombo;
    private ToggleSwitch agentMode;
    private ToggleSwitch debateMode;
    private Button modelPickerButton;
    private ComboBox<Integer> maxRoundsCombo;
    private ComboBox<String> discussionModeCombo;
    private javafx.scene.control.TextField budgetField;
    private List<String> debateAliases = new ArrayList<>();
    private String judgeAlias; // 有裁判模式的裁判模型别名（null=无裁判）
    private Button sendButton;
    private Label statusLabel;
    private VBox emptyState;

    private ChatBubble currentAssistantBubble;
    /** 本轮发送对应的助手气泡引用（提交上下文时读取，避免流式竞态读到下一轮气泡） */
    private ChatBubble pendingTurnAssistant;
    private String lastThinkingText = "";
    private StepCard pendingStepCard;
    private volatile Disposable currentStream;
    private volatile boolean debating = false;
    /** 单模型（直连/Agent）流式进行中：发送钮变为可点的「停止」 */
    private volatile boolean singleRunActive = false;
    /** 当前单模型流是否为 Agent（需要引擎 cancel(runId) 中断） */
    private volatile boolean agentModeRun = false;
    /** Agent 本次运行 runId（中断用） */
    private volatile String agentRunId;
    /** 自动跟随对话底部：用户上翻时暂停（vvalue<0.98 且下滑置 false），新内容/贴底恢复 */
    private volatile boolean autoScrollToBottom = true;
    /** 贴底请求是否已排队（同一脉冲只执行一次，避免多来源请求造成上下震荡） */
    private boolean scrollScheduled = false;
    private String currentDebateSessionId;
    private final Map<String, ChatBubble> debatePlaceholders = new LinkedHashMap<>();
    private final Map<String, javafx.animation.FadeTransition> thinkingAnimations = new LinkedHashMap<>();
    /** 清空上下文按钮防抖（双击只触发一次提示） */
    private long lastClearContextAt;
    /** 运营中心（P2-1）：审计日志查询（audit.enabled=false 时为 null，对话框显示占位） */
    private com.omniforge.core.audit.AuditLogService auditLogService;
    private Stage primaryStage;
    /** 导航栏品牌 Logo 占位（运营中心保存品牌后重建，即时生效） */
    private HBox navBrandSlot;

    @Override
    public void init() {
        ApplicationContext context = AppContextHolder.get();
        if (context != null) {
            modelGateway = context.getBean(ModelGateway.class);
            gatewayProperties = context.getBean(GatewayProperties.class);
            agentEngine = context.getBean(AgentEngine.class);
            toolRegistry = context.getBean(ToolRegistry.class);
            debateEngine = context.getBean(DebateEngine.class);
            debateRecordService = context.getBean(DebateRecordService.class);
            eulaService = context.getBean(EulaService.class);
            licenseService = context.getBean(LicenseService.class);
            contextManager = context.getBean(ContextManager.class);
            knowledgeService = context.getBean(KnowledgeService.class);
            auditLogService = context.getBeanProvider(
                    com.omniforge.core.audit.AuditLogService.class).getIfAvailable();
            pluginBridge = context.getBeanProvider(com.omniforge.ui.plugin.PluginBridge.class)
                    .getIfAvailable();
            enterpriseBridge = context.getBeanProvider(com.omniforge.ui.enterprise.EnterpriseBridge.class)
                    .getIfAvailable();
            chatSessionService = context.getBeanProvider(
                    com.omniforge.core.persistence.service.ChatSessionService.class).getIfAvailable();
        }
    }

    /** 导航栏品牌 Logo 节点：有图片用图片，否则文字「◆ 应用名」（供 refreshBranding 重建） */
    private static javafx.scene.Node buildBrandNode() {
        javafx.scene.image.Image brandLogo = com.omniforge.ui.branding.BrandingManager.logoImage();
        if (brandLogo != null) {
            javafx.scene.image.ImageView logoView = new javafx.scene.image.ImageView(brandLogo);
            logoView.setFitHeight(24);
            logoView.setPreserveRatio(true);
            return logoView;
        }
        Label logo = new Label("◆ " + com.omniforge.ui.branding.BrandingManager.appName());
        logo.getStyleClass().add("nav-logo");
        return logo;
    }

    /** 品牌保存后刷新主窗口（运营中心 onBrandingChanged 回调）：标题 + 导航 Logo；主题色由 ThemeManager 重挂 */
    private void refreshBranding() {
        if (primaryStage != null) {
            primaryStage.setTitle(com.omniforge.ui.branding.BrandingManager.appName());
        }
        if (navBrandSlot != null) {
            navBrandSlot.getChildren().setAll(buildBrandNode());
        }
    }

    @Override
    public void start(Stage stage) {
        instance = this;
        this.primaryStage = stage;
        // 恢复上次主题（ui.yml）与白标（branding.yml），attach 各场景时自动应用
        com.omniforge.ui.theme.ThemeManager.init();
        com.omniforge.ui.branding.BrandingManager.init();
        // P0 诊断：已注册工具清单 + 知识库引擎状态（上下文/检索命中排查）
        if (toolRegistry != null) {
            List<String> toolNames = toolRegistry.all().stream()
                    .map(tool -> tool.spec().name()).sorted().toList();
            log.info("已注册工具：{}", toolNames);
            if (!toolNames.contains("knowledge_search")) {
                log.warn("knowledge_search 工具未注册：知识库检索不可用（Agent 将无法检索挂载文档）");
            }
        }
        if (knowledgeService != null) {
            log.info("知识库引擎：{}", knowledgeService.embeddingDescription());
            if (!knowledgeService.embeddingReady()) {
                log.warn("Embedding 引擎未就绪：首次启动需联网下载模型（{}），或到配置中心配置远程 Embedding",
                        "sentence-transformers/all-MiniLM-L6-v2");
            }
        }
        chatBox = new VBox(10);
        chatBox.setPadding(new Insets(14));
        emptyState = buildEmptyState();
        chatBox.getChildren().add(emptyState);
        chatScroll = new ScrollPane(chatBox);
        chatScroll.setFitToWidth(true);
        chatScroll.getStyleClass().add("chat-scroll");
        // 自动跟随底部：用户上翻阅读历史时暂停跟随，滚回底部自动恢复（P1-4 UI 反馈）
        chatScroll.vvalueProperty().addListener((obs, old, value) -> {
            double v = value == null ? 0 : value.doubleValue();
            if (v >= 0.98) {
                autoScrollToBottom = true;
            } else if (old != null && value.doubleValue() < old.doubleValue()) {
                autoScrollToBottom = false;
            }
        });
        // 内容高度变化时保持贴底：流式增长期间每次布局稳定后单脉冲贴一次底（避免双 setVvalue 上下闪）
        chatBox.heightProperty().addListener((obs, old, height) -> requestScrollBottom());

        modelCombo = new ComboBox<>();
        modelCombo.setPrefWidth(260);
        modelCombo.setMinWidth(260); // P0 修复：模型名称（含提供商标识）完整显示不截断
        // P1：选项展示「别名（提供商）」
        modelCombo.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ModelChoice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.display());
            }
        });
        modelCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ModelChoice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.display());
            }
        });

        modelPickerButton = new Button("选择辩论模型…");
        modelPickerButton.setVisible(false);
        modelPickerButton.setManaged(false);
        modelPickerButton.setOnAction(event -> openModelPicker());

        inputArea = new TextArea();
        setupMentionAutocomplete();
        inputArea.setPromptText("输入消息，Enter 发送，Shift+Enter 换行");
        inputArea.setPrefRowCount(1);
        inputArea.setMaxHeight(64);
        inputArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                send();
            }
        });

        sendButton = new Button("发送");
        sendButton.getStyleClass().add("primary");
        sendButton.setPrefWidth(80); // P0 二轮：固定宽度品牌按钮
        sendButton.setOnAction(event -> send());

        // P1：滑动开关替代原生 CheckBox
        agentMode = new ToggleSwitch("Agent 工具模式")
                .withTooltip("开启后消息经 Agent 引擎处理，工具调用以步骤卡片展示");
        debateMode = new ToggleSwitch("辩论模式")
                .withTooltip("选择 2~10 个模型围绕主题辩论，结果直接展示在对话流中");

        maxRoundsCombo = new ComboBox<>();
        for (int i = 1; i <= 10; i++) {
            maxRoundsCombo.getItems().add(i);
        }
        // 默认 4 轮：1 轮基线 + 连续 3 轮无新内容可触发"集体哑火"熔断（2.3）
        maxRoundsCombo.getSelectionModel().select(Integer.valueOf(4));
        maxRoundsCombo.setPrefWidth(70);
        maxRoundsCombo.setTooltip(new javafx.scene.control.Tooltip("最大辩论轮次"));
        maxRoundsCombo.setVisible(false);
        maxRoundsCombo.setManaged(false);

        discussionModeCombo = new ComboBox<>();
        discussionModeCombo.getItems().addAll("辩论", "讨论", "头脑风暴");
        discussionModeCombo.getSelectionModel().select("辩论");
        discussionModeCombo.setPrefWidth(100);
        discussionModeCombo.setTooltip(new javafx.scene.control.Tooltip("多模型协作模式：辩论（对抗）/ 讨论（圆桌）/ 头脑风暴（发散）"));
        discussionModeCombo.setVisible(false);
        discussionModeCombo.setManaged(false);

        budgetField = new javafx.scene.control.TextField();
        budgetField.setPromptText("预算$（空=不限）");
        budgetField.setPrefWidth(110);
        budgetField.setTooltip(new javafx.scene.control.Tooltip("辩论成本熔断预算（美元），超预算自动终止"));
        budgetField.setVisible(false);
        budgetField.setManaged(false);

        debateMode.selectedProperty().addListener((obs, old, selected) -> {
            modelCombo.setVisible(!selected);
            modelCombo.setManaged(!selected);
            modelPickerButton.setVisible(selected);
            modelPickerButton.setManaged(selected);
            maxRoundsCombo.setVisible(selected);
            maxRoundsCombo.setManaged(selected);
            discussionModeCombo.setVisible(selected);
            discussionModeCombo.setManaged(selected);
            budgetField.setVisible(selected);
            budgetField.setManaged(selected);
        });

        Button themeButton = new Button();
        themeButton.setTooltip(new javafx.scene.control.Tooltip("切换明暗主题（自动保存）"));
        themeButton.setOnAction(event -> {
            com.omniforge.ui.theme.ThemeManager.toggle();
            updateThemeButton(themeButton);
        });
        updateThemeButton(themeButton);

        Button aboutButton = new Button("ℹ 关于");
        aboutButton.setTooltip(new javafx.scene.control.Tooltip("应用信息与版本"));
        aboutButton.setOnAction(event -> AboutDialog.show((Stage) aboutButton.getScene().getWindow()));

        Button settingsButton = new Button("⚙ 配置");
        settingsButton.setOnAction(event -> openSettings());

        recordsButton = new Button("🗂 记录");
        recordsButton.setOnAction(event -> {
            if (debateRecordService != null && gatewayProperties != null) {
                new RecordDialog(debateRecordService,
                        gatewayProperties.getConfigFile().toAbsolutePath().getParent())
                        .show((Stage) recordsButton.getScene().getWindow());
            }
        });

        // 企业版管理（P2-2）：仅 role=admin 显示入口（登录后 applyEnterpriseRole 切换可见性；服务端强校验）
        adminButton = new Button("🏛 管理");
        adminButton.setVisible(false);
        adminButton.setManaged(false);
        adminButton.setTooltip(new javafx.scene.control.Tooltip("用户 / 部门 / 角色管理"));
        adminButton.setOnAction(event -> openAdminDialog());

        // 企业版退出登录（P2-2 评审补）：任何角色可退出并重新登录
        logoutButton = new Button("🚪 退出登录");
        logoutButton.setVisible(enterpriseBridge != null);
        logoutButton.setManaged(enterpriseBridge != null);
        logoutButton.setTooltip(new javafx.scene.control.Tooltip("退出企业版登录，重新选择账号"));
        logoutButton.setOnAction(event -> relogin());

        knowledgeButton = new Button("📚 知识库");
        knowledgeButton.setOnAction(event -> {
            if (enterpriseBridge != null) {
                // 企业模式：服务端共享知识库（成员可管理、viewer 只读由服务端兜底）
                com.omniforge.ui.enterprise.EnterpriseKnowledgeDialog.show(
                        (Stage) knowledgeButton.getScene().getWindow(), enterpriseBridge,
                        "viewer".equalsIgnoreCase(enterpriseRole));
                return;
            }
            if (knowledgeService != null) {
                new KnowledgeDialog(knowledgeService)
                        .show((Stage) knowledgeButton.getScene().getWindow());
            }
        });

        // 插件市场（P1-3）：单机模式显示（本地插件管理；企业版 Agent 走服务端，不适用）
        pluginButton = new Button("🧩 插件");
        boolean showPluginMarket = pluginBridge != null && enterpriseBridge == null;
        pluginButton.setVisible(showPluginMarket);
        pluginButton.setManaged(showPluginMarket);
        pluginButton.setTooltip(new javafx.scene.control.Tooltip(
                "插件市场：浏览/安装/升级/回滚/启停插件（jar / MCP / Agent Skills）"));
        pluginButton.setOnAction(event -> {
            if (pluginBridge != null) {
                new PluginMarketDialog(pluginBridge)
                        .show((Stage) pluginButton.getScene().getWindow());
            }
        });

        // 运营中心（P2-1，2026-09-05）：审计日志可视化查询 + 品牌白标配置
        Button opsButton = new Button("🧭 运营中心");
        opsButton.setTooltip(new javafx.scene.control.Tooltip(
                "审计日志查询（成本/耗时/工具链）+ 品牌白标配置（名称/主题色/Logo）"));
        boolean brandPro = licenseService != null && licenseService.isPro();
        opsButton.setOnAction(event -> new OpsCenterDialog(auditLogService, this::refreshBranding,
                        brandPro, this::openLicenseSettings)
                .show((Stage) opsButton.getScene().getWindow()));

        statusLabel = new Label();
        statusLabel.getStyleClass().add("status");

        // P1：底部两行布局——第一行：模型下拉 + 开关 + 辩论控件；第二行：输入框 + 发送按钮
        HBox controlsRow = new HBox(8, modelCombo, modelPickerButton, agentMode, debateMode,
                discussionModeCombo, maxRoundsCombo, budgetField);
        controlsRow.setAlignment(Pos.CENTER_LEFT);
        HBox inputRow = new HBox(8, inputArea, sendButton);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(inputArea, Priority.ALWAYS);
        // 企业版：多模型协作（对话优先：🧠 在输入旁展开协作发送条，结果以消息回到会话）
        enterpriseDebateButton = new Button("🧠 多模型");
        enterpriseDebateButton.setVisible(false);
        enterpriseDebateButton.setManaged(false);
        enterpriseDebateButton.setTooltip(new javafx.scene.control.Tooltip(
                "多模型协作：选定模板与参与模型后发送，讨论/结论/执行/验收结果逐条回到当前对话"));
        enterpriseDebateButton.setOnAction(event -> toggleCollabComposer());
        inputRow.getChildren().add(inputRow.getChildren().indexOf(sendButton), enterpriseDebateButton);
        // viewer 兜底导出入口（Q7）：默认隐藏，applyFeatureVisibility 中 viewer 才显示
        inputDraftButton.setVisible(false);
        inputDraftButton.setManaged(false);
        inputRow.getChildren().add(inputRow.getChildren().indexOf(sendButton), inputDraftButton);
        // 协作发送条（默认隐藏；对话优先 D1-D4）
        collabTemplate.getItems().addAll("圆桌讨论", "正反辩论", "发散头脑风暴",
                "方案评审", "一致性收敛");
        collabTemplate.setValue("方案评审");
        // 轮次：数字输入（1~10，服务端硬上限钳制；空/非法按 1）
        collabRounds.setPrefColumnCount(2);
        collabRounds.setPrefWidth(56);
        collabRounds.setTooltip(new javafx.scene.control.Tooltip("讨论轮次：1~10，默认 1"));
        collabRounds.setTextFormatter(new javafx.scene.control.TextFormatter<String>(change -> {
            String text = change.getControlNewText();
            return text.matches("\\d{0,2}") ? change : null;
        }));
        collabJudge.getItems().add("（无裁判）");
        collabJudge.setValue("（无裁判）");
        // 参与模型下拉多选：按钮点击弹勾选面板（内容在 loadCollabModels 时按模型构建）
        collabModelsPicker.setPrefWidth(180);
        collabModelsPicker.setTooltip(new javafx.scene.control.Tooltip(
                "选择参与协作的模型（至少 2 个；展开后逐项勾选）"));
        collabModelsPicker.setOnAction(e -> toggleCollabModelsPopup());
        collabModelsPanel.setStyle("-fx-background-color: -fx-background;"
                + " -fx-background-radius: 6; -fx-border-color: derive(-fx-color, 25%);"
                + " -fx-border-radius: 6; -fx-padding: 6;");
        javafx.scene.control.ScrollPane collabModelsScroller = new javafx.scene.control.ScrollPane(collabModelsPanel);
        collabModelsScroller.setFitToWidth(true);
        collabModelsScroller.setPrefWidth(240);
        collabModelsScroller.setPrefHeight(150);
        collabModelsScroller.setMaxHeight(260);
        collabModelsScroller.setStyle("-fx-background-color: transparent;"
                + " -fx-background-insets: 0; -fx-padding: 0;");
        collabModelsPopup.setAutoHide(false);
        collabModelsPopup.getContent().add(collabModelsScroller);
        Label cHint = new Label("发送后整条闭环在本对话内逐条汇报；可随时点「🗂 档案」回看/删除");
        cHint.getStyleClass().add("muted");
        collabArchiveButton.setOnAction(e -> openCollabArchive());
        collabDraftButton.setTooltip(new javafx.scene.control.Tooltip(
                "把本会话的对话与多模型协作整理成 Markdown 草稿（复制 / 另存为 .md）"));
        collabDraftButton.setOnAction(e -> documentDraft());
        // viewer 兜底导出入口：与 🧠 并排（viewer 无写权限看不到协作条，但导出只读可用）
        inputDraftButton.setTooltip(new javafx.scene.control.Tooltip(
                "把本会话的对话与多模型协作整理成 Markdown 草稿（复制 / 另存为 .md）"));
        inputDraftButton.setOnAction(e -> documentDraft());
        javafx.scene.layout.FlowPane collabLine = new javafx.scene.layout.FlowPane(8, 4);
        collabLine.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        // B1（U3）：自动执行开关——关（默认）= 讨论+结论后暂停，执行+验收需点击续跑（闸在费用大头）
        autoExecToggle.setSelected(false);
        javafx.scene.control.Tooltip.install(autoExecToggle, new javafx.scene.control.Tooltip(
                "开：讨论→结论→执行→验收自动跑完（执行阶段调用工具产生费用）；\n"
                        + "关（默认）：讨论与结论完成后暂停，由你决定是否「⚙ 执行并验收」。"));
        collabLine.getChildren().addAll(
                new Label("模板"), collabTemplate,
                new Label("参与模型"), collabModelsPicker,
                new Label("裁判"), collabJudge,
                new Label("轮次"), collabRounds,
                autoExecToggle,
                collabArchiveButton,
                collabDraftButton);
        collabLine.setRowValignment(javafx.geometry.VPos.CENTER);
        // 等比例统一交给显示后的实测对齐（alignCollabRowHeights），不在此硬编码高度
        collabComposer.getChildren().setAll(collabLine, cHint);
        collabComposer.setVisible(false);
        collabComposer.setManaged(false);

        // 白标 Logo：放进可重建的占位（运营中心保存品牌后 refreshBranding 即时重建）
        navBrandSlot = new HBox();
        navBrandSlot.getChildren().setAll(buildBrandNode());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        clearContextButton = new Button("🧹 清空上下文");
        clearContextButton.setTooltip(new javafx.scene.control.Tooltip(
                "清空本次会话的上下文记忆（模型将不再记得此前对话）"));
        clearContextButton.setOnAction(event -> {
            // 防双击重复提示（500ms 内重复点击只执行一次）
            long now = System.currentTimeMillis();
            if (now - lastClearContextAt < 500) {
                return;
            }
            lastClearContextAt = now;
            if (contextManager != null) {
                contextManager.clear(currentSessionKey());
            }
            appendSystem("上下文已清空：模型不再记得此前对话。");
        });
        // 历史会话侧栏（问题一）：+ 新建对话 / 搜索 / 日期分组树
        Button newChatButton = new Button("➕ 新建对话");
        newChatButton.setMaxWidth(Double.MAX_VALUE);
        newChatButton.setOnAction(event -> startNewChat());

        historySearchField = new javafx.scene.control.TextField();
        historySearchField.setPromptText("搜索会话…");
        historySearchField.textProperty().addListener((obs, old, text) -> refreshHistoryTree());

        historyTree = new javafx.scene.control.TreeView<>();
        historyTree.setShowRoot(false);
        historyTree.getStyleClass().add("history-tree");
        historyTree.setCellFactory(view -> new HistoryCell());
        historyTree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            HistoryEntry entry = item == null ? null : item.getValue();
            if (entry != null && entry.sessionId() != null
                    && !entry.sessionId().equals(currentChatSessionId)) {
                openChatSession(entry);
            }
        });
        refreshHistoryTree();

        // 左栏内容：单机 = 历史树；企业 = 左侧会话列表（布局重构评审：两种模式对话列表都常驻左侧）
        VBox standalonePane = new VBox(8, newChatButton, historySearchField, historyTree);
        VBox.setVgrow(historyTree, Priority.ALWAYS);
        // 企业版：左侧会话列表（新建/重命名/删除/双击恢复；viewer 只读时隐藏写操作）
        VBox enterprisePane = null;
        if (enterpriseBridge != null) {
            enterpriseNewButton = new Button("＋ 新会话");
            enterpriseNewButton.setMaxWidth(Double.MAX_VALUE);
            enterpriseNewButton.setOnAction(event -> startEnterpriseNewSession());
            enterpriseRenameButton = new Button("✎ 重命名");
            enterpriseDeleteButton = new Button("🗑 删除");
            enterpriseDeleteButton.getStyleClass().add("danger");
            enterpriseRenameButton.setOnAction(event -> renameEnterpriseSelected());
            enterpriseDeleteButton.setOnAction(event -> deleteEnterpriseSelected());
            HBox enterpriseActionRow = new HBox(6, enterpriseRenameButton, enterpriseDeleteButton);
            enterpriseActionRow.setAlignment(Pos.CENTER_LEFT);
            enterpriseSessionsList = new javafx.scene.control.ListView<>();
            enterpriseSessionsList.getStyleClass().add("history-tree");
            enterpriseSessionsList.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
                @Override
                protected void updateItem(com.omniforge.ui.enterprise.EnterpriseBridge.SessionItem item,
                                          boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setTooltip(null);
                        return;
                    }
                    String time = item.createdAt() == null ? ""
                            : item.createdAt().format(java.time.format.DateTimeFormatter
                                    .ofPattern("yyyy-MM-dd HH:mm"));
                    setText(item.name());
                    setTooltip(time.isBlank() ? null : new javafx.scene.control.Tooltip(time));
                }
            });
            enterpriseSessionsList.setOnMouseClicked(event -> {
                // D8 反馈：单击即打开（对齐单机侧栏单击习惯；双击同样有效）
                var selected = enterpriseSessionsList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    restoreEnterpriseSession(selected.id());
                }
            });
            enterprisePane = new VBox(8, enterpriseNewButton, enterpriseActionRow, enterpriseSessionsList);
            VBox.setVgrow(enterpriseSessionsList, Priority.ALWAYS);
        }

        // 左下：功能菜单折叠区（导航重设计①：按功能域分组，替代原先零散堆叠的顶部按钮）
        Button settingsToggleButton = new Button("⚙ 功能 ▸");
        settingsToggleButton.setMaxWidth(Double.MAX_VALUE);
        VBox settingsItems = new VBox(6);
        menuGroups = new ArrayList<>();
        addMenuGroupBox(settingsItems, "账户 / 会话", aboutButton, logoutButton);
        addMenuGroupBox(settingsItems, "管理", opsButton, adminButton);
        addMenuGroupBox(settingsItems, "数据 / 知识", knowledgeButton, recordsButton);
        addMenuGroupBox(settingsItems, "扩展", pluginButton);
        addMenuGroupBox(settingsItems, "偏好", settingsButton);
        settingsItems.setVisible(false);
        settingsItems.setManaged(false);
        settingsToggleButton.setOnAction(event -> {
            boolean show = !settingsItems.isVisible();
            settingsItems.setVisible(show);
            settingsItems.setManaged(show);
            settingsToggleButton.setText(show ? "⚙ 功能 ▾" : "⚙ 功能 ▸");
            if (show) {
                refreshMenuGroups();
            }
        });

        historySidebar = new VBox(8);
        historySidebar.getStyleClass().add("history-sidebar");
        historySidebar.setPrefWidth(240);
        // vgrow 链不能断：sideContent 撑满侧栏后，内层 pane 也要撑满 sideContent，
        // 否则历史树/会话列表停在默认首选高度（约 400px），条目一多就出不该有的滚动条
        javafx.scene.layout.VBox innerSidePane = enterpriseBridge != null && enterprisePane != null
                ? enterprisePane : standalonePane;
        VBox.setVgrow(innerSidePane, Priority.ALWAYS);
        VBox sideContent = new VBox(8, innerSidePane);
        VBox.setVgrow(sideContent, Priority.ALWAYS);
        historySidebar.getChildren().setAll(sideContent,
                new javafx.scene.control.Separator(), settingsToggleButton, settingsItems);

        // 侧栏折叠按钮（导航栏最左）
        Button sidebarToggle = new Button("☰");
        sidebarToggle.setTooltip(new javafx.scene.control.Tooltip("显示/隐藏左侧对话列表"));
        sidebarToggle.setOnAction(event -> {
            boolean show = !historySidebar.isVisible();
            historySidebar.setVisible(show);
            historySidebar.setManaged(show);
        });

        // 顶栏精简：品牌 + 清空上下文 + 主题（布局重构评审：设置类已入左下折叠区）
        HBox nav = new HBox(8, sidebarToggle, navBrandSlot, spacer, clearContextButton, themeButton);
        nav.setPadding(new Insets(6, 12, 6, 12)); // 导航压缩（P0 三修：消息区上移）
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.getStyleClass().add("navbar");

        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(2, 0, 0, 0));

        BorderPane root = new BorderPane();
        root.setTop(nav);
        root.setCenter(chatScroll);
        VBox inputPanel = new VBox(6, controlsRow, collabComposer, inputRow, statusBar);
        inputPanel.setPadding(new Insets(8, 12, 8, 12));
        inputPanel.getStyleClass().add("input-panel"); // P0 二轮：对话区与操作区分割线
        root.setBottom(inputPanel);
        // 对话列表侧栏（单机=历史树 / 企业=会话列表；两种模式均常驻左侧）
        root.setLeft(historySidebar);

        // 统一功能可见性初始状态（登录/重登后由 applyEnterpriseRole 再收敛）
        applyFeatureVisibility();

        // 参与模型下拉面板：点击主场景任意处（除触发器按钮外）自动收起
        root.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (collabModelsPopup.isShowing()
                    && event.getTarget() != collabModelsPicker) {
                collabModelsPopup.hide();
            }
        });

        Scene scene = new Scene(root, 1280, 760);
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        com.omniforge.ui.theme.ThemeManager.attach(scene);
        stage.setTitle(com.omniforge.ui.branding.BrandingManager.appName());
        stage.setScene(scene);
        stage.setMaximized(true); // 默认占满可用区域（用户反馈：窗体应占全部）
        stage.show();

        // 系统托盘：安装成功后关闭窗口 = 隐藏到托盘；不支持时保持关闭即退出
        if (com.omniforge.ui.tray.SystemTraySupport.install(stage)) {
            stage.setOnCloseRequest(event -> {
                event.consume();
                com.omniforge.ui.tray.SystemTraySupport.hideToTray(stage);
            });
        }
        // 开机自启路径自愈（B2 Q4-A）：安装版升级/换目录后静默改写自启项指向
        com.omniforge.ui.tray.AutoStartSupport.healIfNeeded();

        // Phase 4 安全底线：首次启动强制签署 EULA，拒绝即退出
        if (eulaService != null && !eulaService.isAccepted()) {
            if (!EulaDialog.showAndWait(stage, eulaService)) {
                Platform.exit();
                return;
            }
        }
        // 企业版：登录门禁（取消/失败退出）+ 隐藏本地模型控件（模型由服务端决定）
        if (enterpriseBridge != null) {
            EnterpriseLoginDialog.LoginOutcome outcome = EnterpriseLoginDialog.showAndWait(stage,
                    enterpriseBridge.initialServerUrl(),
                    enterpriseBridge::login, enterpriseBridge::saveServerUrl);
            if (outcome == null || !outcome.ok()) {
                Platform.exit();
                return;
            }
            for (javafx.scene.Node node : List.of(modelCombo, modelPickerButton, agentMode, debateMode,
                    discussionModeCombo, maxRoundsCombo, budgetField, clearContextButton)) {
                node.setVisible(false);
                node.setManaged(false);
            }
            // 角色感知（企业版 P2/P2-2）：状态栏展示角色 + 管理入口可见性（admin）
            applyEnterpriseRole(outcome.role());
            return;
        }
        refreshState();
    }

    /**
     * 工具执行人工确认入口（HITL，A1）：由 app 装配层的 ToolApproval 实现调用。
     * <p>无实例 / 主窗口未就绪 → 返回 {@code false}（等同拒绝，宁严勿松）并 WARN；
     * 正常路径在 FX 线程弹确认框，60s 超时计拒绝（Q2-A）。</p>
     */
    public static boolean confirmToolExecution(com.omniforge.common.spi.ToolSpec spec,
                                               java.util.Map<String, Object> params) {
        OmniForgeApplication app = instance;
        if (app == null || app.primaryStage == null) {
            log.warn("工具 {} 需人工确认但 GUI 未就绪，按拒绝处理（宁严勿松）", spec.name());
            return false;
        }
        return com.omniforge.ui.ToolConfirmationDialog.ask(app.primaryStage, spec, params);
    }

    /** 企业版：应用登录角色（状态栏 + 功能可见性 + viewer 只读禁输入） */
    private void applyEnterpriseRole(String role) {
        enterpriseRole = role == null ? "" : role;
        String label = enterpriseRole.isBlank() ? "用户" : enterpriseRole;
        statusLabel.setText("企业版 · 已连接服务端（角色：" + label + "）");
        applyFeatureVisibility();
        boolean viewer = "viewer".equalsIgnoreCase(enterpriseRole);
        if (inputArea != null && sendButton != null) {
            inputArea.setDisable(viewer);
            sendButton.setDisable(viewer);
            if (viewer) {
                appendSystem("当前为只读角色（viewer），可查看历史会话，不能发起对话。");
            }
        }
        refreshEnterpriseSessions();
    }

    /**
     * 统一功能可见性规则表（导航重设计①）：[功能] × [standalone/enterprise] × [角色]。
     * 取代散落的 setVisible 逻辑——登录/重登/模式切换后调用即可收敛。
     */
    private void applyFeatureVisibility() {
        boolean enterpriseMode = enterpriseBridge != null;
        boolean admin = "admin".equalsIgnoreCase(enterpriseRole);
        boolean viewer = "viewer".equalsIgnoreCase(enterpriseRole);
        boolean writable = enterpriseMode && !viewer;
        // 按能力可用性显示（用户反馈：不再按模式删除入口）：
        // 知识库/记录在 GUI 装配即可用 → 单机与企业模式均显示；清空上下文仅单机（企业无本地会话记忆）
        setVisibleManaged(knowledgeButton, knowledgeService != null);
        setVisibleManaged(recordsButton, debateRecordService != null);
        setVisibleManaged(clearContextButton, !enterpriseMode);
        setVisibleManaged(pluginButton, pluginBridge != null);
        // 账户与会话级：企业模式
        setVisibleManaged(logoutButton, enterpriseMode);
        setVisibleManaged(adminButton, enterpriseMode && admin);
        // 企业协作与左栏会话写操作：viewer 只读隐藏
        setVisibleManaged(enterpriseDebateButton, writable);
        setVisibleManaged(collabDraftButton, writable);
        setVisibleManaged(enterpriseNewButton, writable);
        setVisibleManaged(enterpriseRenameButton, writable);
        setVisibleManaged(enterpriseDeleteButton, writable);
        // D5 Q7：导出=只读操作，viewer 可见可用（collabLine 被写角色独占，viewer 走输入行入口）
        setVisibleManaged(inputDraftButton, enterpriseMode && viewer);
        refreshMenuGroups();
    }

    private static void setVisibleManaged(javafx.scene.Node node, boolean show) {
        if (node == null) {
            return;
        }
        node.setVisible(show);
        node.setManaged(show);
    }

    private VBox buildEmptyState() {
        Label icon = new Label("🤖");
        icon.getStyleClass().add("empty-icon");
        // 标题去重（P0 三修）：不再重复应用名（导航栏已有「◆ OmniForge」）
        Label title = new Label("开始新的对话");
        title.getStyleClass().add("empty-title");
        Label hint = new Label("点左下「⚙ 功能」→「偏好：⚙ 配置」添加模型，即可开始对话");
        hint.getStyleClass().add("status");
        VBox box = new VBox(8, icon, title, hint);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(36)); // 顶部空间压缩（P0 三修）
        box.getStyleClass().add("empty-state");
        return box;
    }

    /** 左栏折叠菜单：一组标题 + 全宽按钮，装箱为独立组容器（便于隐藏空分组，导航①） */
    private void addMenuGroupBox(VBox parent, String title, Button... buttons) {
        boolean anyPresent = false;
        for (Button button : buttons) {
            if (button != null) {
                anyPresent = true;
                break;
            }
        }
        if (!anyPresent) {
            return;
        }
        Label header = new Label(title);
        header.getStyleClass().add("status");
        VBox group = new VBox(6, header);
        for (Button button : buttons) {
            if (button == null) {
                continue;
            }
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAlignment(Pos.CENTER_LEFT);
            group.getChildren().add(button);
        }
        parent.getChildren().add(group);
        menuGroups.add(group);
    }

    /** 组内没有任何可见按钮时隐藏整组（企业模式/角色不同导致入口为空的分组不再显示） */
    private void refreshMenuGroups() {
        for (VBox group : menuGroups) {
            boolean anyVisible = false;
            for (javafx.scene.Node node : group.getChildren()) {
                if (node instanceof Button button && button.isVisible()) {
                    anyVisible = true;
                    break;
                }
            }
            group.setVisible(anyVisible);
            group.setManaged(anyVisible);
        }
    }

    public void refreshState() {
        // 企业版状态栏由登录流程维护（本地模型列表不适用）
        if (enterpriseBridge != null) {
            return;
        }
        if (modelGateway == null) {
            return;
        }
        var models = modelGateway.availableModels();
        List<ModelChoice> items = new ArrayList<>(models.stream()
                .map(model -> new ModelChoice(model.alias(), model.providerName(), false))
                .sorted(java.util.Comparator.comparing(ModelChoice::alias))
                .toList());
        // Pro 专属：智能路由选项（选中后请求不带模型别名，由路由器按复杂度选模型）
        if (licenseService != null && licenseService.isPro()) {
            items.add(0, new ModelChoice(null, null, true));
        }
        ModelChoice selected = modelCombo.getSelectionModel().getSelectedItem();
        modelCombo.getItems().setAll(items);
        // 保持用户手动选择不被刷新覆盖；仅在无选择时默认选中第一项
        if (selected != null && items.contains(selected)) {
            modelCombo.getSelectionModel().select(selected);
        } else if (!items.isEmpty() && modelCombo.getSelectionModel().isEmpty()) {
            modelCombo.getSelectionModel().selectFirst();
        }
        GatewayUsage usage = modelGateway.usage();
        statusLabel.setText(models.isEmpty()
                ? "⚠ 尚无可用模型 —— 点左下「⚙ 功能」→「偏好：⚙ 配置」添加"
                : String.format("已加载 %d 个模型 · 累计调用 %d 次 · 估算成本 $%.4f",
                        models.size(), usage.totalCalls(), usage.totalEstimatedCostUsd()));
    }

    /** 模型下拉选项（P1）：别名 + 提供商展示；autoRoute = Pro 智能路由占位 */
    private record ModelChoice(String alias, String provider, boolean autoRoute) {

        String display() {
            return autoRoute ? AUTO_ROUTE_LABEL
                    : provider == null || provider.isBlank()
                    ? alias : alias + "（" + provider + "）";
        }
    }

    // ---------- 历史会话侧栏（问题一） ----------

    /** 历史树条目：sessionId 为 null 表示日期分组节点 */
    private record HistoryEntry(String name, String sessionId) {
    }

    /** 当前会话的上下文键：新会话用默认键，历史会话按 ID 隔离（切换会话即切换记忆） */
    private String currentSessionKey() {
        return currentChatSessionId == null ? UI_SESSION_ID : "ui:chat:" + currentChatSessionId;
    }

    /** 日期分组：今天 / 昨天 / 本周 / 更早（按最后活动时间） */
    private static String groupOf(java.time.LocalDateTime time) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate date = time.toLocalDate();
        if (date.equals(today)) {
            return "今天";
        }
        if (date.equals(today.minusDays(1))) {
            return "昨天";
        }
        if (!date.isBefore(today.minusDays(7))) {
            return "本周";
        }
        return "更早";
    }

    /** 重建历史树（搜索过滤 + 分组 + 恢复当前会话选中态） */
    private void refreshHistoryTree() {
        if (historyTree == null) {
            return;
        }
        String query = historySearchField.getText() == null
                ? "" : historySearchField.getText().trim().toLowerCase();
        TreeItem<HistoryEntry> root = new TreeItem<>(new HistoryEntry("root", null));
        java.util.Map<String, TreeItem<HistoryEntry>> groups = new LinkedHashMap<>();
        for (String group : List.of("今天", "昨天", "本周", "更早")) {
            TreeItem<HistoryEntry> node = new TreeItem<>(new HistoryEntry(group, null));
            node.setExpanded(true);
            groups.put(group, node);
        }
        if (chatSessionService != null) {
            for (var info : chatSessionService.list()) {
                if (!query.isBlank() && !info.name().toLowerCase().contains(query)) {
                    continue;
                }
                groups.get(groupOf(info.updatedAt())).getChildren()
                        .add(new TreeItem<>(new HistoryEntry(info.name(), info.id())));
            }
        }
        groups.values().stream()
                .filter(group -> !group.getChildren().isEmpty())
                .forEach(root.getChildren()::add);
        historyTree.setRoot(root);
        historyTree.setShowRoot(false);
        // 恢复当前会话选中态（不触发切换：ID 相同被监听器忽略）
        if (currentChatSessionId != null) {
            selectSessionNode(root, currentChatSessionId);
        }
    }

    private void selectSessionNode(TreeItem<HistoryEntry> node, String sessionId) {
        for (TreeItem<HistoryEntry> child : node.getChildren()) {
            HistoryEntry entry = child.getValue();
            if (sessionId.equals(entry.sessionId())) {
                historyTree.getSelectionModel().select(child);
                return;
            }
            selectSessionNode(child, sessionId);
        }
    }

    /** 树单元格：分组节点弱化样式，会话节点右键菜单 */
    private final class HistoryCell extends javafx.scene.control.TreeCell<HistoryEntry> {

        @Override
        protected void updateItem(HistoryEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setTooltip(null);
                setContextMenu(null);
                getStyleClass().remove("history-group");
                return;
            }
            setText(item.name());
            // 长会话名被单元格截断（水平滚动条已隐藏）：悬停 tooltip 显示全名
            setTooltip(item.sessionId() == null ? null
                    : new javafx.scene.control.Tooltip(item.name()));
            if (item.sessionId() == null) {
                getStyleClass().add("history-group");
                setContextMenu(null);
            } else {
                getStyleClass().remove("history-group");
                setContextMenu(sessionMenu(item));
            }
        }
    }

    /** 会话右键菜单：重命名 / 导出 / 删除 */
    private ContextMenu sessionMenu(HistoryEntry entry) {
        MenuItem renameItem = new MenuItem("✏ 重命名");
        renameItem.setOnAction(event -> renameSession(entry));
        MenuItem exportItem = new MenuItem("📄 导出");
        exportItem.setOnAction(event -> exportSession(entry));
        MenuItem deleteItem = new MenuItem("🗑 删除");
        deleteItem.setOnAction(event -> deleteSession(entry));
        ContextMenu menu = new ContextMenu(renameItem, exportItem, deleteItem);
        com.omniforge.ui.theme.ThemeManager.attach(menu);
        return menu;
    }

    /** 新建对话：清空主界面与上下文，回到新会话 */
    private void startNewChat() {
        currentChatSessionId = null;
        if (contextManager != null) {
            contextManager.clear(currentSessionKey());
        }
        clearAllMessages();
        refreshHistoryTree();
    }

    /** 点击切换会话：主界面渲染该会话历史，上下文记忆同步切换 */
    private void openChatSession(HistoryEntry entry) {
        if (chatSessionService == null) {
            return;
        }
        currentChatSessionId = entry.sessionId();
        if (contextManager != null) {
            contextManager.clear(currentSessionKey());
            for (var message : chatSessionService.messages(entry.sessionId())) {
                contextManager.append(currentSessionKey(),
                        "user".equals(message.getRole()) ? ContextRole.USER : ContextRole.ASSISTANT,
                        message.getContent());
            }
        }
        thinkingAnimations.values().forEach(javafx.animation.FadeTransition::stop);
        thinkingAnimations.clear();
        debatePlaceholders.clear();
        currentAssistantBubble = null;
        pendingTurnAssistant = null;
        chatBox.getChildren().clear();
        for (var message : chatSessionService.messages(entry.sessionId())) {
            ChatBubble bubble = "user".equals(message.getRole())
                    ? appendBubble("user", message.getContent())
                    : appendAssistant(message.getContent(), null);
            bubble.showActions(true);
        }
        if (chatBox.getChildren().isEmpty()) {
            chatBox.getChildren().add(emptyState);
        }
        appendSystem("已切换到会话：" + entry.name());
    }

    private void renameSession(HistoryEntry entry) {
        if (chatSessionService == null) {
            return;
        }
        javafx.scene.control.TextInputDialog prompt =
                new javafx.scene.control.TextInputDialog(entry.name());
        prompt.setTitle("重命名会话");
        prompt.setHeaderText(null);
        prompt.setContentText("会话名称：");
        prompt.initOwner(historyTree.getScene().getWindow());
        com.omniforge.ui.theme.ThemeManager.attachDialog(prompt);
        prompt.showAndWait().ifPresent(name -> {
            if (name == null || name.isBlank()) {
                return;
            }
            try {
                chatSessionService.rename(entry.sessionId(), name.trim());
                refreshHistoryTree();
            } catch (Exception e) {
                Toast.show(null, "重命名失败：" + e.getMessage(), false);
            }
        });
    }

    private void deleteSession(HistoryEntry entry) {
        if (chatSessionService == null) {
            return;
        }
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "删除会话「" + entry.name() + "」及其全部消息？此操作不可恢复。",
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        confirm.setTitle("删除会话");
        confirm.setHeaderText(null);
        confirm.initOwner(historyTree.getScene().getWindow());
        com.omniforge.ui.theme.ThemeManager.attachDialog(confirm);
        confirm.showAndWait().ifPresent(choice -> {
            if (choice != javafx.scene.control.ButtonType.OK) {
                return;
            }
            chatSessionService.delete(entry.sessionId());
            if (entry.sessionId().equals(currentChatSessionId)) {
                startNewChat();
            } else {
                refreshHistoryTree();
            }
            Toast.show(null, "已删除会话：" + entry.name(), true);
        });
    }

    private void exportSession(HistoryEntry entry) {
        if (chatSessionService == null || gatewayProperties == null) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                String markdown = chatSessionService.exportMarkdown(entry.sessionId());
                java.nio.file.Path exportsDir =
                        gatewayProperties.getConfigFile().toAbsolutePath().getParent().resolve("exports");
                java.nio.file.Files.createDirectories(exportsDir);
                java.nio.file.Path target = exportsDir.resolve(
                        "chat-" + entry.sessionId().substring(0, 8) + ".md");
                java.nio.file.Files.writeString(target, markdown);
                Platform.runLater(() -> Toast.show(null, "已导出：" + target.toAbsolutePath(), true));
            } catch (Exception e) {
                Platform.runLater(() -> Toast.show(null, "导出失败：" + e.getMessage(), false));
            }
        });
    }

    /** 企业版：发送消息（服务端执行 Agent，虚拟线程阻塞调用，结果回 UI 线程） */
    private void startEnterpriseChat(String text) {
        setSending(true);
        appendBubble("user", text);
        String sessionId = enterpriseSessionId;
        currentAssistantBubble = appendAssistant("",
                bubble -> runEnterpriseChat(sessionId, text, bubble));
        runEnterpriseChat(sessionId, text, currentAssistantBubble);
    }

    /** 企业版对话执行（首次与 🔄 重新生成共用；虚拟线程阻塞调用） */
    private void runEnterpriseChat(String sessionId, String text, ChatBubble target) {
        setSending(true);
        target.setText("");
        target.removeStyle("bubble-error");
        target.showActions(false);
        currentAssistantBubble = target;
        Thread.ofVirtual().start(() -> {
            try {
                var outcome = enterpriseBridge.chat(sessionId, text);
                Platform.runLater(() -> {
                    appendChunk(outcome.text());
                    if (outcome.sessionId() != null && !outcome.sessionId().isBlank()) {
                        enterpriseSessionId = outcome.sessionId();
                    }
                    finishSend();
                    refreshEnterpriseSessions();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    appendChunk("（调用失败：" + e.getMessage() + "）");
                    markAssistantError();
                    finishSend();
                    handleEnterpriseError(e);
                });
            }
        });
    }

    /** 企业版：刷新左栏会话列表并高亮当前会话 */
    private void refreshEnterpriseSessions() {
        if (enterpriseBridge == null || enterpriseSessionsList == null) {
            return;
        }
        // D8：强制重拉（原实现读登录缓存，协作新建的会话永远不出现）
        List<com.omniforge.ui.enterprise.EnterpriseBridge.SessionItem> items;
        try {
            items = enterpriseBridge.refreshSessions();
        } catch (Exception e) {
            log.warn("会话列表刷新失败：{}", e.getMessage());
            items = enterpriseBridge.sessions();
        }
        enterpriseSessionsList.getItems().setAll(items);
        if (enterpriseSessionId == null) {
            enterpriseSessionsList.getSelectionModel().clearSelection();
        } else {
            items.stream().filter(item -> item.id().equals(enterpriseSessionId)).findFirst()
                    .ifPresent(enterpriseSessionsList.getSelectionModel()::select);
        }
    }

    /** 企业版：新会话（清空对话区） */
    private void startEnterpriseNewSession() {
        enterpriseSessionId = null;
        chatBox.getChildren().clear();
        chatBox.getChildren().add(emptyState);
        appendSystem("已开始新会话，输入消息即可发送。");
        refreshEnterpriseSessions();
    }

    /** 企业版：重命名左栏选中会话 */
    private void renameEnterpriseSelected() {
        var selected = enterpriseSessionsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        javafx.scene.control.TextInputDialog prompt =
                new javafx.scene.control.TextInputDialog(selected.name());
        prompt.setTitle("重命名会话");
        prompt.setHeaderText("为「" + selected.name() + "」输入新名称");
        prompt.initOwner((Stage) chatScroll.getScene().getWindow());
        com.omniforge.ui.theme.ThemeManager.attachDialog(prompt);
        var name = prompt.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        try {
            enterpriseBridge.renameSession(selected.id(), name.get().trim());
            refreshEnterpriseSessions();
        } catch (Exception e) {
            handleEnterpriseError(e);
        }
    }

    /** 企业版：删除左栏选中会话（确认后；删除当前会话则回空白） */
    private void deleteEnterpriseSelected() {
        var selected = enterpriseSessionsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "删除会话「" + selected.name() + "」？其消息记录一并删除。",
                javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
        alert.setTitle("确认");
        alert.initOwner((Stage) chatScroll.getScene().getWindow());
        com.omniforge.ui.theme.ThemeManager.attachDialog(alert);
        if (!alert.showAndWait().map(button -> button == javafx.scene.control.ButtonType.OK)
                .orElse(false)) {
            return;
        }
        try {
            enterpriseBridge.deleteSession(selected.id());
            if (enterpriseSessionId != null && enterpriseSessionId.equals(selected.id())) {
                enterpriseSessionId = null;
                chatBox.getChildren().clear();
                chatBox.getChildren().add(emptyState);
            }
            refreshEnterpriseSessions();
        } catch (Exception e) {
            handleEnterpriseError(e);
        }
    }

    /** 企业版管理对话框（P2-2）：登录态失效 → 经 handleEnterpriseError 触发重登 */
    private void openAdminDialog() {
        if (enterpriseBridge == null || !"admin".equalsIgnoreCase(enterpriseRole)) {
            return;
        }
        com.omniforge.ui.enterprise.EnterpriseAdminDialog.show(
                (Stage) chatScroll.getScene().getWindow(),
                enterpriseBridge.admin(),
                () -> handleEnterpriseError(
                        new com.omniforge.ui.enterprise.EnterpriseBridge.AuthExpiredException("登录已过期", null)));
    }

    /** 企业版：多模型协作（对话优先）——🧠 开合协作发送条 */
    private void toggleCollabComposer() {
        if (enterpriseBridge == null) {
            return;
        }
        collabComposerOn = !collabComposerOn;
        collabComposer.setVisible(collabComposerOn);
        collabComposer.setManaged(collabComposerOn);
        if (collabComposerOn && !collabModelsLoaded) {
            loadCollabModels();
        }
        if (collabComposerOn) {
            // 协作条刚显示：待 CSS/皮肤就绪后按实测自然高度统一行内控件（不裁字、又等高）
            Platform.runLater(() -> Platform.runLater(this::alignCollabRowHeights));
        }
    }

    /**
     * 协作条行内控件等高：以皮肤真实自然高度最大值统一（不用固定像素——固定值
     * 高于某控件皮肤需求会裁字，低于另一控件又参差）。只统一 min/pref，不设
     * maxHeight 上限，皮肤按需上浮时也绝不会裁文字。
     */
    private void alignCollabRowHeights() {
        javafx.scene.control.Control[] row = new javafx.scene.control.Control[]{
                collabTemplate, collabModelsPicker, collabJudge, collabRounds,
                collabArchiveButton, collabDraftButton};
        double max = 0;
        for (javafx.scene.control.Control c : row) {
            double h = c.prefHeight(-1);
            if (h > 0 && !Double.isNaN(h)) {
                max = Math.max(max, h);
            }
        }
        if (max <= 0) {
            return;
        }
        for (javafx.scene.control.Control c : row) {
            c.setMinHeight(max);
            c.setPrefHeight(max);
        }
    }

    /** 协作发送条所需模型清单（多选列表 + 裁判下拉） */
    private void loadCollabModels() {
        Thread.ofVirtual().start(() -> {
            try {
                List<com.omniforge.ui.enterprise.EnterpriseBridge.ModelOption> models =
                        enterpriseBridge.models();
                Platform.runLater(() -> {
                    if (models.size() < 2) {
                        appendSystem("服务端可用模型不足 2 个，无法发起多模型协作（当前 "
                                + models.size() + " 个）。请先在服务端 models.yml 配置 ≥2 个模型。");
                        return;
                    }
                    collabModelsLoaded = true;
                    List<String> aliases = models.stream()
                            .map(com.omniforge.ui.enterprise.EnterpriseBridge.ModelOption::alias)
                            .toList();
                    collabModelsPanel.getChildren().clear();
                    collabModelChecks.clear();
                    for (String alias : aliases) {
                        javafx.scene.control.CheckBox check = new javafx.scene.control.CheckBox(alias);
                        check.setMaxWidth(Double.MAX_VALUE);
                        check.selectedProperty().addListener(
                                (obs, old, val) -> updateCollabModelsHint());
                        collabModelChecks.put(alias, check);
                        collabModelsPanel.getChildren().add(check);
                    }
                    collabJudge.getItems().clear();
                    collabJudge.getItems().add("（无裁判）");
                    collabJudge.getItems().addAll(aliases);
                    collabJudge.setValue("（无裁判）");
                    // 预勾前两个（默认即可发起）
                    List<String> pre = aliases.stream().limit(2).toList();
                    pre.forEach(alias -> {
                        javafx.scene.control.CheckBox check = collabModelChecks.get(alias);
                        if (check != null) {
                            check.setSelected(true);
                        }
                    });
                    updateCollabModelsHint();
                });
            } catch (Exception e) {
                Platform.runLater(() -> appendSystem("多模型模型清单加载失败：" + e.getMessage()));
            }
        });
    }

    /** 下拉触发器：在按钮正下方弹出/收起勾选面板（点击外部自动收起） */
    private void toggleCollabModelsPopup() {
        if (collabModelsPopup.isShowing()) {
            collabModelsPopup.hide();
            return;
        }
        javafx.geometry.Bounds bounds = collabModelsPicker.localToScreen(
                collabModelsPicker.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        collabModelsPopup.show(collabModelsPicker,
                bounds.getMinX(), bounds.getMaxY() + 2);
    }

    // ---------- D7 方向 A：@ 提及自动补全 ----------

    /** 输入 @ 时弹出可用模型候选（企业模式）；点击插入「@别名 」，免手输精确别名 */
    private void setupMentionAutocomplete() {
        inputArea.textProperty().addListener((obs, old, now) -> updateMentionPopup());
        inputArea.caretPositionProperty().addListener((obs, old, now) -> updateMentionPopup());
        inputArea.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                hideMentionPopup();
            }
        });
        inputArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE
                    && mentionPopup != null && mentionPopup.isShowing()) {
                mentionPopup.hide();
                event.consume();
            }
        });
    }

    private void updateMentionPopup() {
        if (enterpriseBridge == null || collabModelChecks.isEmpty()
                || inputArea.getScene() == null) {
            hideMentionPopup();
            return;
        }
        String text = inputArea.getText();
        int caret = inputArea.getCaretPosition();
        if (caret < 0 || caret > text.length()) {
            hideMentionPopup();
            return;
        }
        String frag = com.omniforge.ui.collab.CollabMentionParser.mentionFragment(
                text.substring(0, caret));
        if (frag == null) {
            hideMentionPopup();
            return;
        }
        var hits = com.omniforge.ui.collab.CollabMentionParser.suggestModels(
                frag, List.copyOf(collabModelChecks.keySet()));
        if (hits.isEmpty()) {
            hideMentionPopup();
            return;
        }
        showMentionPopup(hits, frag, caret);
    }

    private void showMentionPopup(List<String> hits, String fragment, int caret) {
        if (mentionPopup == null) {
            mentionListBox = new javafx.scene.layout.VBox(2);
            mentionListBox.getStyleClass().add("tray-menu");
            mentionListBox.setPadding(new javafx.geometry.Insets(4));
            mentionPopup = new javafx.stage.Popup();
            mentionPopup.setAutoFix(true);
            mentionPopup.setAutoHide(true);
            mentionPopup.getContent().add(mentionListBox);
            com.omniforge.ui.theme.ThemeManager.attach(mentionPopup.getScene());
        }
        mentionListBox.getChildren().clear();
        for (String alias : hits) {
            Button item = new Button("@" + alias);
            item.setMaxWidth(Double.MAX_VALUE);
            item.setOnAction(event -> {
                String text = inputArea.getText();
                int start = Math.max(0, caret - fragment.length() - 1);
                String replacement = "@" + alias + " ";
                inputArea.setText(text.substring(0, start) + replacement
                        + text.substring(Math.min(caret, text.length())));
                inputArea.positionCaret(start + replacement.length());
                hideMentionPopup();
            });
            mentionListBox.getChildren().add(item);
        }
        var bounds = inputArea.localToScreen(inputArea.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        double h = Math.min(hits.size() * 34 + 10, 240);
        mentionPopup.show(inputArea, bounds.getMinX(), bounds.getMinY() - h - 6);
    }

    private void hideMentionPopup() {
        if (mentionPopup != null && mentionPopup.isShowing()) {
            mentionPopup.hide();
        }
    }

    /** 参与模型已选摘要：刷新触发器按钮文本（已选 N/M）与 tooltip 明细 */
    private void updateCollabModelsHint() {
        List<String> selected = selectedCollabModels();
        int total = collabModelChecks.size();
        if (total == 0) {
            collabModelsPicker.setText("参与模型 ▾");
            collabModelsPicker.setTooltip(new javafx.scene.control.Tooltip(
                    "参与模型尚未加载，稍后重试"));
            return;
        }
        collabModelsPicker.setText("参与模型（" + selected.size() + "/" + total + "）▾");
        String detail = selected.isEmpty() ? "未选择：请至少勾选 2 个"
                : "已选 " + selected.size() + " 个：" + String.join("、", selected)
                + (selected.size() < 2 ? "（需 ≥2 个）" : "");
        collabModelsPicker.setTooltip(new javafx.scene.control.Tooltip(detail));
    }

    /** 当前勾选的参与模型别名（顺序 = 服务端模型顺序） */
    private List<String> selectedCollabModels() {
        return collabModelChecks.entrySet().stream()
                .filter(e -> e.getValue().isSelected())
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    /** 轮次解析：空/非法 → 1；超界（服务端亦钳制 1~10）→ 就近收敛 */
    private int parseCollabRounds() {
        try {
            int parsed = Integer.parseInt(collabRounds.getText().trim());
            return Math.max(1, Math.min(parsed, 10));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 档案（治理次级入口）：打开协作档案窗 */
    private void openCollabArchive() {
        if (enterpriseBridge == null) {
            return;
        }
        javafx.stage.Window window = enterpriseDebateButton.getScene() == null
                ? null : enterpriseDebateButton.getScene().getWindow();
        if (window instanceof javafx.stage.Stage stage) {
            com.omniforge.ui.enterprise.CollabRunDialog.show(stage, enterpriseBridge,
                    "viewer".equalsIgnoreCase(enterpriseRole),
                    summary -> Platform.runLater(() -> appendSystem(summary)));
        }
    }

    /**
     * 对话内协作（D7 参数化）：①讨论（全轮次平铺）→②结论→【自动执行开：③执行→④验收 | 关：暂停待续跑】。
     *
     * @param displayText 用户原始输入（含 @提及，进用户气泡）；topic 清洗后主题（进协作 run）
     */
    private void startEnterpriseCollab(String displayText, String topic, List<String> aliases,
                                       String template, int rounds, boolean autoExec,
                                       String judgeAlias) {
        setSending(true);
        appendBubble("user", displayText);
        if (aliases == null || aliases.size() < 2) {
            appendSystem("请至少选择 2 个参与模型后再发起。");
            finishSend();
            return;
        }
        java.util.Map<String, Object> preset = collabPreset(template);
        final String mode = (String) preset.get("mode");
        final String systemText = (String) preset.get("systemText");
        final List<String> aliasList = List.copyOf(aliases);
        // B1 Q5-A：发送时捕获开关与会话（运行中切换不影响本轮；Q4-A 守卫依据）
        final String sessionIdAtSend = enterpriseSessionId;
        appendSystem("🧠 已提交「" + template + "」：" + oneLine(topic, 60)
                + "（参与 " + String.join(" + ", aliasList) + " · " + rounds + " 轮"
                + (autoExec ? "，自动执行" : "，执行前需确认") + "）");
        Thread.ofVirtual().start(() -> {
            try {
                // D5 Q1/Q6：传当前会话（新会话直接开协作时 enterpriseSessionId 为空 → 服务端自动建会话）
                var run = enterpriseBridge.collabCreate(topic, aliasList,
                        judgeAlias, mode, rounds, 0.5, systemText, sessionIdAtSend);
                // D8 Q4-A：采纳新会话为当前会话（侧栏即时可见）
                String runSession = run.sessionId();
                if ((sessionIdAtSend == null || sessionIdAtSend.isBlank())
                        && runSession != null && !runSession.isBlank()) {
                    Platform.runLater(() -> {
                        enterpriseSessionId = runSession;
                        refreshEnterpriseSessions();
                    });
                }
                final String persistId = runSession != null && !runSession.isBlank()
                        ? runSession : sessionIdAtSend;
                renderDiscussion(run.id(), aliasList, template, persistId);
                String conclusionText;
                try {
                    var concluded = enterpriseBridge.collabConclude(run.id());
                    conclusionText = concluded.conclusion();
                    persistCollabStage(persistId, com.omniforge.ui.collab.CollabStageMessage.stage(
                            "━━ ② 结论 ━━\n" + conclusionText));
                } catch (Exception concludeFailure) {
                    // D7 反馈：结论模型超时/异常（服务端已标 REVISED 可重试）→ 给出重试检查点而非整体失败
                    Platform.runLater(() -> {
                        setSending(false);
                        appendSystem("⚠ 结论生成失败（模型超时/异常）：" + concludeFailure.getMessage());
                        appendCollabCheckpoint(run.id(), judgeAlias, aliasList, persistId, true);
                    });
                    return;
                }
                appendCollabSystem("② 结论", conclusionText);
                if (!autoExec) {
                    Platform.runLater(() -> {
                        setSending(false);
                        appendCollabCheckpoint(run.id(), judgeAlias, aliasList, persistId, false);
                    });
                    return;
                }
                finishCollab(run.id(), judgeAlias, aliasList, persistId);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setSending(false);
                    appendSystem("协作失败（已中止）：" + e.getMessage());
                });
            }
        });
    }

    /** D8 Q5-A：协作阶段落库（fire-and-forget 虚拟线程；失败仅告警不中断协作流） */
    private void persistCollabStage(String sessionId, String json) {
        if (enterpriseBridge == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                enterpriseBridge.appendSessionMessage(sessionId, json);
            } catch (Exception e) {
                log.warn("协作阶段落库失败：{}", e.getMessage());
            }
        });
    }

    /**
     * D7 方向 B：讨论记录全内联（Q3-A + Q6-B）。结构化 JSON → 每轮「━━ 第 N 轮 ━━」分隔 +
     * 每模型色点气泡全文；旧 run 回退 ◆ 段单层；再回退单全文气泡。不再 200 字摘要。
     */
    private void renderDiscussion(String runId, List<String> aliases, String template,
                                  String persistId) {
        String data;
        String legacy;
        try {
            data = enterpriseBridge.collabTranscriptData(runId);
        } catch (Exception e) {
            data = null;
        }
        try {
            legacy = enterpriseBridge.collabTranscript(runId);
        } catch (Exception e) {
            legacy = null;
        }
        var result = com.omniforge.ui.collab.CollabTranscriptParser.parse(data, legacy);
        // 数据抓取在虚拟线程；UI 渲染必须回 FX 线程（appendSystem/气泡直接操作场景图）
        // D8：渲染的同时逐条落库（statement 全文含轮次/别名）
        Platform.runLater(() -> {
            String finalCaption = result.caption() == null ? "" : result.caption();
            String divider = "━━ ① 多模型讨论（" + template + " · " + aliases.size() + " 模型"
                    + (finalCaption.isBlank() ? "" : " · " + finalCaption) + "）━━";
            appendSystem(divider);
            persistCollabStage(persistId, com.omniforge.ui.collab.CollabStageMessage.stage(divider));
            if (result.singleFallback() != null) {
                appendSystem(result.singleFallback());
                persistCollabStage(persistId, com.omniforge.ui.collab.CollabStageMessage.stage(
                        result.singleFallback()));
                return;
            }
            boolean multiRound = result.blocks().size() > 1;
            for (var block : result.blocks()) {
                if (multiRound && block.round() > 0) {
                    appendSystem("━━ 第 " + block.round() + " 轮 ━━");
                }
                for (var statement : block.statements()) {
                    appendDebateBubble(statement.alias(), statement.text());
                    persistCollabStage(persistId, com.omniforge.ui.collab.CollabStageMessage.statement(
                            statement.alias(), block.round(), statement.text()));
                }
            }
        });
    }

    /**
     * ③执行 → ④验收 共用段（B1）：自动全链与手动「⚙ 执行并验收」两路径共用；
     * 调用方负责 setSending(true)；完成/失败在此恢复发送态。
     */
    private void finishCollab(String runId, String judgeAlias, List<String> aliases, String persistId) {
        try {
            var executed = enterpriseBridge.collabExecute(runId, null, aliases.get(0));
            // D7 Q4-A：执行输出全文内联（不再 500 字截断）；D8：落库
            appendCollabSystem("③ 执行完成", executed.executionOutput());
            persistCollabStage(persistId, com.omniforge.ui.collab.CollabStageMessage.stage(
                    "━━ ③ 执行完成 ━━\n" + executed.executionOutput()));
            var reviewed = enterpriseBridge.collabReview(runId, judgeAlias);
            boolean pass = "done".equals(reviewed.status());
            String reviewText = "━━ ④ 验收：" + (pass ? "✅ 通过" : "⚠ 未通过（可在档案中重试）")
                    + " ━━\n" + reviewed.reviewReason();
            appendCollabSystem("④ 验收：" + (pass ? "✅ 通过" : "⚠ 未通过（可在档案中重试）"),
                    reviewed.reviewReason());
            persistCollabStage(persistId, com.omniforge.ui.collab.CollabStageMessage.stage(reviewText));
            Platform.runLater(() -> {
                setSending(false);
                appendSystem("✅ 本轮协作已完成，结论与结果已在上方；点「🗂 档案」可查看完整讨论/回看。");
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                setSending(false);
                appendSystem("协作失败（已中止）：" + e.getMessage());
            });
        }
    }

    /**
     * 暂停检查点（B1 Q3-A + D7 反馈增强）：⏸ 气泡 + 常显按钮。
     * retryConclusion=true（结论生成失败时）：额外提供「♻ 重试结论」，成功后回到普通暂停态。
     * 点击续跑经会话守卫（Q4-A）：跨会话仅提示不执行。
     */
    private void appendCollabCheckpoint(String runId, String judgeAlias, List<String> aliases,
                                        String sessionIdAtSend, boolean retryConclusion) {
        persistCollabStage(sessionIdAtSend, com.omniforge.ui.collab.CollabStageMessage.checkpoint(
                retryConclusion ? "⏸ 结论生成失败，等待重试或打住" : "⏸ 协作已暂停，等待确认执行"));
        hideEmptyState();
        Label label = new Label(retryConclusion
                ? "⏸ 结论生成失败（模型超时/异常）：可重试结论，或就此打住稍后在 🗂 档案处理。"
                : "⏸ 协作已暂停：讨论与结论已就绪。执行阶段将调用工具（产生费用），确认后继续。");
        label.setWrapText(true);
        label.getStyleClass().add("status");
        Button proceed = new Button("⚙ 执行并验收");
        proceed.getStyleClass().add("primary");
        Button stop = new Button("▣ 就此打住");
        HBox buttons = new HBox(8, proceed, stop);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox checkpoint = new VBox(8, label, buttons);
        checkpoint.getStyleClass().add("collab-checkpoint");
        checkpoint.setPadding(new Insets(10));
        if (retryConclusion) {
            Button retry = new Button("♻ 重试结论");
            buttons.getChildren().add(0, retry);
            retry.setOnAction(event -> {
                if (!java.util.Objects.equals(enterpriseSessionId, sessionIdAtSend)) {
                    appendSystem("该协作属于其他会话，请切回原会话后操作。");
                    return;
                }
                chatBox.getChildren().remove(checkpoint);
                setSending(true);
                Thread.ofVirtual().start(() -> {
                    try {
                        var run = enterpriseBridge.collabConclude(runId);
                        persistCollabStage(sessionIdAtSend, com.omniforge.ui.collab.CollabStageMessage.stage(
                                "━━ ② 结论 ━━\n" + run.conclusion()));
                        Platform.runLater(() -> {
                            setSending(false);
                            appendCollabSystem("② 结论", run.conclusion());
                            appendCollabCheckpoint(runId, judgeAlias, aliases, sessionIdAtSend, false);
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> {
                            setSending(false);
                            appendSystem("⚠ 结论重试仍失败：" + e.getMessage());
                            appendCollabCheckpoint(runId, judgeAlias, aliases, sessionIdAtSend, true);
                        });
                    }
                });
            });
        }
        proceed.setOnAction(event -> {
            if (!java.util.Objects.equals(enterpriseSessionId, sessionIdAtSend)) {
                appendSystem("该协作属于其他会话，请切回原会话后继续，或在 🗂 档案中操作。");
                return;
            }
            chatBox.getChildren().remove(checkpoint);
            setSending(true);
            Thread.ofVirtual().start(() -> finishCollab(runId, judgeAlias, aliases, sessionIdAtSend));
        });
        stop.setOnAction(event -> {
            chatBox.getChildren().remove(checkpoint);
            appendSystem("已打住：该协作停在「结论」状态，后续可在 🗂 档案中继续执行/重试。");
        });
        chatBox.getChildren().add(checkpoint);
        requestScrollBottom();
    }

    /** D5 文档产出（Q2/Q5）：拉取本会话消息 + 该会话协作 → 合成 Markdown → 弹预览小窗 */
    private void documentDraft() {
        if (enterpriseBridge == null) {
            return;
        }
        final String sessionId = enterpriseSessionId;
        if (sessionId == null || sessionId.isBlank()) {
            appendSystem("当前会话还没有内容可导出（请先在会话中对话或发起协作）。");
            return;
        }
        javafx.stage.Window owner = primaryStage != null ? primaryStage
                : (collabDraftButton.getScene() == null ? null
                : collabDraftButton.getScene().getWindow());
        Thread.ofVirtual().name("omniforge-doc-draft", 0).start(() -> {
            try {
                List<com.omniforge.ui.enterprise.EnterpriseBridge.ChatMessage> messages =
                        enterpriseBridge.messages(sessionId);
                var collabPage = enterpriseBridge.collabRuns(sessionId, 0, 100);
                List<com.omniforge.ui.enterprise.DocDraftBuilder.CollabRun> collabs =
                        new java.util.ArrayList<>();
                for (var run : collabPage.items()) {
                    String transcript = "";
                    try {
                        transcript = enterpriseBridge.collabTranscript(run.id());
                    } catch (Exception ignored) {
                        // 讨论全文拉取失败不阻塞整篇草稿（该段摘要为空）
                    }
                    collabs.add(new com.omniforge.ui.enterprise.DocDraftBuilder.CollabRun(
                            run.id(), run.mode(), run.topic(), run.aliases(),
                            run.judgeAlias(), run.maxRounds(), run.status(),
                            run.conclusion(), run.executionOutput(), run.reviewVerdict(),
                            run.reviewReason(), transcript, run.createdAt()));
                }
                String markdown = com.omniforge.ui.enterprise.DocDraftBuilder.build(
                        currentSessionName(), sessionId,
                        messages.stream()
                                .map(m -> new com.omniforge.ui.enterprise.DocDraftBuilder.ChatLine(
                                        m.role(), m.content(), m.createdAt()))
                                .toList(),
                        collabs);
                final String fileName = "doc-" + sessionId.substring(0,
                        Math.min(8, sessionId.length())) + ".md";
                Platform.runLater(() -> {
                    if (owner instanceof javafx.stage.Stage stage) {
                        com.omniforge.ui.enterprise.DocDraftDialog.show(
                                stage, markdown, fileName);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> appendSystem("文档产出失败：" + e.getMessage()));
            }
        });
    }

    /** 当前企业会话名（左栏列表匹配；无则回退「未命名会话」） */
    private String currentSessionName() {
        if (enterpriseSessionId == null || enterpriseSessionId.isBlank()) {
            return "未命名会话";
        }
        try {
            return enterpriseBridge.sessions().stream()
                    .filter(item -> enterpriseSessionId.equals(item.id()))
                    .map(com.omniforge.ui.enterprise.EnterpriseBridge.SessionItem::name)
                    .findFirst().orElse("未命名会话");
        } catch (Exception e) {
            return "未命名会话";
        }
    }

    /** D5 Q2/Q5：关键词命中检测（写成文档/写周报/写方案/生成总结；不区分大小写/全半角） */
    private static boolean isDocDraftKeyword(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalizeFullHalfWidth(text);
        return normalized.contains("写成文档")
                || normalized.contains("写周报")
                || normalized.contains("写方案")
                || normalized.contains("生成总结");
    }

    /** 全角字符转半角（Q5：命中词不区分大小写/全半角） */
    private static String normalizeFullHalfWidth(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '！' && c <= '～') {
                sb.append((char) (c - '！' + '!'));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void appendCollabSystem(String title, String body) {
        Platform.runLater(() -> appendSystem("━━ " + title + " ━━\n"
                + (body == null || body.isBlank() ? "（无内容）" : body)));
    }

    private String transcriptHead(String runId) {
        try {
            return truncateLines(enterpriseBridge.collabTranscript(runId), 200);
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncateLines(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String oneLine(String value, int max) {
        return truncateLines(value, max);
    }

    private static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "done" -> "已完成";
            case "revised" -> "待重试";
            case "aborted" -> "中止";
            default -> status == null ? "" : status;
        };
    }

    /** 模板 → (mode, 角色指令)；复用协作模板文案 */
    private static java.util.Map<String, Object> collabPreset(String template) {
        String mode = "discussion";
        String systemText = "你是圆桌讨论成员：围绕议题从不同角度补充信息与分析，指出他人盲点，协助形成结论与行动项。";
        if ("正反辩论".equals(template)) {
            mode = "debate";
            systemText = "你是一场正反辩论中的一方：正方主张采纳/自研，反方主张反对/外购，"
                    + "每轮给出一条论据并直接反驳对方上轮观点，语言精炼、给出理由与权衡。";
        } else if ("发散头脑风暴".equals(template)) {
            mode = "brainstorm";
            systemText = "你是头脑风暴成员：先充分发散产出尽可能多且不同的候选/观点（含大胆想法），"
                    + "不要互相批评；最后每人给出对可行性的简短评估。";
        } else if ("方案评审".equals(template)) {
            systemText = "你是方案评审专家：先给出你推荐的方案与理由，然后对其它方案提出具体的风险/"
                    + "成本/合规质疑，要求对方回应；目标是通过质询收敛到可执行建议与行动项。";
        } else if ("一致性收敛".equals(template)) {
            systemText = "你们的目标是收敛出团队一致结论：先亮明观点与分歧点，再逐步求同存异、"
                    + "合并意见，减少重复；分歧无法消除时明确标注并给出默认建议。";
        }
        return java.util.Map.of("mode", mode, "systemText", systemText);
    }

    private record EnterpriseDebateParams(String topic, String mode, List<String> aliases,
                                          String judgeAlias, int maxRounds, Double budgetUsd) {
    }

    private void showEnterpriseDebateDialog(
            List<com.omniforge.ui.enterprise.EnterpriseBridge.ModelOption> models) {
        if (models.size() < 2) {
            appendSystem("服务端可用模型不足 2 个，无法发起多模型协作（当前 " + models.size() + " 个）。"
                    + "请先在服务端 models.yml 配置 ≥2 个可用模型。");
            return;
        }
        javafx.scene.control.Dialog<EnterpriseDebateParams> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("🎤 多模型协作");
        dialog.initOwner((Stage) chatScroll.getScene().getWindow());

        javafx.scene.control.TextArea topicArea = new javafx.scene.control.TextArea();
        topicArea.setPromptText("协作主题…");
        topicArea.setPrefRowCount(3);

        javafx.scene.control.ComboBox<String> modeCombo = new javafx.scene.control.ComboBox<>();
        modeCombo.getItems().addAll("辩论（对抗）", "圆桌讨论", "头脑风暴");
        modeCombo.getSelectionModel().selectFirst();
        javafx.scene.control.ComboBox<Integer> roundsCombo = new javafx.scene.control.ComboBox<>();
        for (int i = 1; i <= 10; i++) {
            roundsCombo.getItems().add(i);
        }
        roundsCombo.getSelectionModel().select(Integer.valueOf(3));
        javafx.scene.control.TextField budgetField2 = new javafx.scene.control.TextField();
        budgetField2.setPromptText("预算 $（空=不限）");
        budgetField2.setPrefColumnCount(8);

        javafx.scene.control.ComboBox<String> judgeCombo = new javafx.scene.control.ComboBox<>();
        judgeCombo.getItems().add("（无裁判）");
        models.forEach(model -> judgeCombo.getItems().add(model.alias()));
        judgeCombo.getSelectionModel().selectFirst();

        javafx.scene.control.Label modelHint = new Label("参与模型（至少 2 个，≤ 席位上限）");
        modelHint.getStyleClass().add("status");
        VBox modelBox = new VBox(4);
        List<javafx.scene.control.CheckBox> modelChecks = new java.util.ArrayList<>();
        models.forEach(model -> {
            javafx.scene.control.CheckBox check =
                    new javafx.scene.control.CheckBox(model.alias()
                            + (model.providerName() == null || model.providerName().isBlank()
                            ? "" : "（" + model.providerName() + "）"));
            check.setUserData(model.alias());
            modelChecks.add(check);
            modelBox.getChildren().add(check);
        });

        dialog.getDialogPane().setContent(new VBox(8,
                new Label("主题"), topicArea,
                new javafx.scene.layout.HBox(12, new Label("模式"), modeCombo,
                        new Label("最大轮次"), roundsCombo, new Label("预算"), budgetField2),
                new javafx.scene.layout.HBox(12, new Label("裁判模型"), judgeCombo),
                modelHint, modelBox));
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        dialog.setResultConverter(button -> {
            if (button != javafx.scene.control.ButtonType.OK) {
                return null;
            }
            String topic = topicArea.getText() == null ? "" : topicArea.getText().strip();
            List<String> aliases = modelChecks.stream()
                    .filter(javafx.scene.control.CheckBox::isSelected)
                    .map(check -> (String) check.getUserData()).toList();
            if (topic.isEmpty()) {
                Toast.show(null, "请填写协作主题", false);
                return null;
            }
            if (aliases.size() < 2) {
                Toast.show(null, "请至少勾选 2 个参与模型", false);
                return null;
            }
            String mode = switch (modeCombo.getValue() == null ? "辩论（对抗）" : modeCombo.getValue()) {
                case "圆桌讨论" -> "discussion";
                case "头脑风暴" -> "brainstorm";
                default -> "debate";
            };
            String judge = judgeCombo.getValue() == null ? null : judgeCombo.getValue();
            if (judge != null && judge.startsWith("（")) {
                judge = null;
            }
            Double budget = null;
            if (budgetField2.getText() != null && !budgetField2.getText().isBlank()) {
                try {
                    budget = Double.parseDouble(budgetField2.getText().strip());
                } catch (NumberFormatException ignored) {
                    Toast.show(null, "预算格式错误，已按不限处理", false);
                }
            }
            return new EnterpriseDebateParams(topic, mode, aliases, judge,
                    roundsCombo.getValue() == null ? 3 : roundsCombo.getValue(), budget);
        });
        com.omniforge.ui.theme.ThemeManager.attachDialog(dialog);

        dialog.showAndWait().ifPresent(params -> {
            appendSystem("🎤 协作进行中（模式 " + params.mode() + " · "
                    + params.aliases().size() + " 模型 × " + params.maxRounds() + " 轮）…");
            Thread.ofVirtual().start(() -> {
                try {
                    var outcome = enterpriseBridge.debate(
                            new com.omniforge.ui.enterprise.EnterpriseBridge.DebateRun(
                                    params.topic(), params.aliases(), params.judgeAlias(),
                                    params.mode(), params.maxRounds(), params.budgetUsd()));
                    Platform.runLater(() -> {
                        appendBubble("user", "🎤 协作：" + params.topic()
                                + "（" + String.join("、", params.aliases()) + "）");
                        appendBubble("assistant", outcome.text());
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        appendSystem("协作失败：" + e.getMessage());
                        handleEnterpriseError(e);
                    });
                }
            });
        });
    }

    /** 企业版退出登录：清登录态 + 重置界面 → 重新登录（取消则退出应用） */
    private void relogin() {
        if (enterpriseBridge == null) {
            return;
        }
        enterpriseBridge.logout();
        enterpriseRole = "";
        enterpriseSessionId = null;
        if (adminButton != null) {
            adminButton.setVisible(false);
            adminButton.setManaged(false);
        }
        if (inputArea != null && sendButton != null) {
            inputArea.setDisable(false);
            sendButton.setDisable(false);
        }
        chatBox.getChildren().clear();
        chatBox.getChildren().add(emptyState);
        EnterpriseLoginDialog.LoginOutcome outcome = EnterpriseLoginDialog.showAndWait(
                (Stage) chatScroll.getScene().getWindow(),
                enterpriseBridge.initialServerUrl(),
                enterpriseBridge::login, enterpriseBridge::saveServerUrl);
        if (outcome == null || !outcome.ok()) {
            Platform.exit();
            return;
        }
        applyEnterpriseRole(outcome.role());
        appendSystem("已重新登录。");
    }

    private static void wrapEnterprise(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 企业版错误处理（P2）：登录过期 → 触发重新登录；服务端不可达 → 明确提示 */
    private void handleEnterpriseError(Exception e) {
        boolean authExpired = e instanceof com.omniforge.ui.enterprise.EnterpriseBridge.AuthExpiredException
                || (e.getCause() instanceof com.omniforge.ui.enterprise.EnterpriseBridge.AuthExpiredException);
        if (authExpired && enterpriseBridge != null) {
            appendSystem("登录已过期，请重新登录");
            EnterpriseLoginDialog.LoginOutcome outcome = EnterpriseLoginDialog.showAndWait(
                    (Stage) chatScroll.getScene().getWindow(),
                    enterpriseBridge.initialServerUrl(),
                    enterpriseBridge::login, enterpriseBridge::saveServerUrl);
            if (outcome == null || !outcome.ok()) {
                Platform.exit();
                return;
            }
            applyEnterpriseRole(outcome.role());
            appendSystem("已重新登录，请重试");
            return;
        }
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        if (message.contains("无法连接服务端")) {
            appendSystem("⚠ " + message + "（请确认服务端已启动，地址见登录框）");
        } else {
            appendSystem("企业版调用失败：" + message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 企业版：恢复会话（null = 新会话：清空对话区并重置会话） */
    private void restoreEnterpriseSession(String sessionId) {
        if (sessionId == null) {
            enterpriseSessionId = null;
            chatBox.getChildren().clear();
            chatBox.getChildren().add(emptyState);
            appendSystem("已开始新会话");
            refreshEnterpriseSessions();
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                var messages = enterpriseBridge.messages(sessionId);
                Platform.runLater(() -> {
                    enterpriseSessionId = sessionId;
                    chatBox.getChildren().clear();
                    messages.forEach(message -> {
                        // D8：协作阶段消息还原（statement=色点气泡；stage/checkpoint=系统样式；解析失败=平文本）
                        if ("collab".equals(message.role())) {
                            var stage = com.omniforge.ui.collab.CollabStageMessage.parse(message.content());
                            if (stage != null && com.omniforge.ui.collab.CollabStageMessage.KIND_STATEMENT
                                    .equals(stage.kind())) {
                                appendDebateBubble(stage.alias(), stage.text());
                            } else if (stage != null) {
                                appendSystem(stage.text());
                            } else {
                                appendSystem(message.content());
                            }
                            return;
                        }
                        ChatBubble bubble = appendBubble(
                                "user".equals(message.role()) ? "user" : "assistant", message.content());
                        bubble.showActions(true); // 历史消息直接显示操作行
                    });
                    appendSystem("已恢复会话（" + messages.size() + " 条消息）");
                    refreshEnterpriseSessions();
                });
            } catch (Exception e) {
                Platform.runLater(() -> appendSystem("恢复会话失败：" + e.getMessage()));
            }
        });
    }

    /** 主题按钮文案显示"切换后"的目标主题 */
    private static void updateThemeButton(Button themeButton) {
        boolean dark = com.omniforge.ui.theme.UiSettings.THEME_DARK
                .equals(com.omniforge.ui.theme.ThemeManager.currentTheme());
        themeButton.setText(dark ? "☀️ 明亮" : "🌙 暗色");
    }

    private void openSettings() {
        if (modelGateway == null) {
            return;
        }
        new SettingsDialog(modelGateway, gatewayProperties, this::refreshState)
                .show((Stage) modelCombo.getScene().getWindow());
    }

    /** 运营中心「前往激活 Pro」：打开配置中心并定位 License 授权模块 */
    private void openLicenseSettings() {
        if (modelGateway == null) {
            return;
        }
        javafx.stage.Window owner = primaryStage != null ? primaryStage
                : modelCombo.getScene() == null ? null : modelCombo.getScene().getWindow();
        if (owner == null) {
            return;
        }
        new SettingsDialog(modelGateway, gatewayProperties, this::refreshState).showLicense(owner);
    }

    private void send() {
        // 停止辩论必须最先处理（与输入内容无关——用户点击停止时输入框通常为空）
        if (debateMode.isSelected() && debating) {
            if (currentDebateSessionId != null) {
                debateEngine.cancel(currentDebateSessionId);
            }
            if (currentStream != null) {
                currentStream.dispose();
            }
            appendSystem("辩论已中断");
            setDebating(false);
            return;
        }
        // 单模型（直连/Agent）流式运行中：再次点发送 = 停止（与辩论停止同入口，避免无停止兜底转圈）
        if (singleRunActive) {
            if (agentModeRun && agentRunId != null && agentEngine != null) {
                agentEngine.cancel(agentRunId);
            }
            if (currentStream != null) {
                currentStream.dispose();
            }
            if (pendingStepCard != null) {
                pendingStepCard.abort();
                pendingStepCard = null;
            }
            appendSystem("已停止生成");
            finishSend();
            return;
        }
        if (modelGateway == null || modelGateway.availableModels().isEmpty()) {
            appendSystem("模型网关未就绪：请先点击右上角「⚙ 配置」添加模型。");
            return;
        }
        String text = inputArea.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        inputArea.clear();

        // 企业版：Agent 在服务端执行（会话续接由 enterpriseSessionId 承载）；
        // D7 方向 A：@提及自然发起（≥2 个 @ → 协作；无 @ → 普通对话/协作条显式路径）
        if (enterpriseBridge != null) {
            // D5 Q2/Q5：关键词命中直接产出草稿（不调模型、不占对话流）
            if (isDocDraftKeyword(text)) {
                documentDraft();
                return;
            }
            var mention = com.omniforge.ui.collab.CollabMentionParser.parse(
                    text, List.copyOf(collabModelChecks.keySet()));
            if (!mention.unknownAliases().isEmpty() && mention.aliases().isEmpty()) {
                inputArea.setText(text);
                appendSystem("未找到模型 @" + String.join(" @", mention.unknownAliases())
                        + "。可用：" + String.join("、", collabModelChecks.keySet()));
                return;
            }
            if (mention.isCollab()) {
                startEnterpriseCollab(text, mention.topic(), mention.aliases(),
                        mention.template(), mention.rounds(), autoExecToggle.isSelected(), null);
                return;
            }
            if (mention.isSingle()) {
                inputArea.setText(text);
                appendSystem("协作需要至少 2 个 @模型（如 @"
                        + String.join(" @", collabModelChecks.keySet().stream().limit(2).toList())
                        + "）；单模型请直接发送消息。");
                return;
            }
            if (collabComposerOn && collabComposer.isVisible()) {
                String judge = collabJudge.getValue();
                List<String> selected = new ArrayList<>(selectedCollabModels());
                if (selected.size() < 2) {
                    appendSystem("请至少勾选 2 个参与模型后再发起。");
                    finishSend();
                    return;
                }
                startEnterpriseCollab(text, text, selected,
                        collabTemplate.getValue() == null ? "圆桌讨论" : collabTemplate.getValue(),
                        parseCollabRounds(), autoExecToggle.isSelected(),
                        judge == null || judge.startsWith("（") ? null : judge);
            } else {
                startEnterpriseChat(text);
            }
            return;
        }

        if (debateMode.isSelected()) {
            sendDebate(text);
            return;
        }

        ModelChoice choice = modelCombo.getSelectionModel().getSelectedItem();
        String alias = choice == null ? null : choice.alias();
        // 智能路由选项：直连/Agent 均 alias=null 走路由器（Batch2：身份策略/复杂度，
        // Agent 亦有请求文本与身份，不再仅回退默认模型）
        if (choice != null && choice.autoRoute()) {
            alias = null;
        }
        String resolvedAlias = alias;
        boolean useAgent = agentMode.isSelected() && agentEngine != null;
        setSending(true);
        if (currentStream != null) {
            currentStream.dispose();
        }
        appendBubble("user", text);
        currentAssistantBubble = appendAssistant("",
                bubble -> regenerateTurn(resolvedAlias, text, useAgent, bubble));
        pendingTurnAssistant = currentAssistantBubble;
        lastThinkingText = "";
        pendingStepCard = null;

        if (useAgent) {
            startAgentChat(resolvedAlias, text);
        } else {
            startDirectChat(resolvedAlias, text);
        }
    }

    /** 🔄 重新生成（DeepSeek 风格）：清空该条助手气泡，用同一输入重新执行 */
    private void regenerateTurn(String alias, String text, boolean useAgent, ChatBubble target) {
        if (currentStream != null) {
            currentStream.dispose();
        }
        setSending(true);
        target.setText("");
        target.removeStyle("bubble-error");
        target.showActions(false);
        currentAssistantBubble = target;
        pendingTurnAssistant = target;
        lastThinkingText = "";
        pendingStepCard = null;
        if (useAgent) {
            startAgentChat(alias, text);
        } else {
            startDirectChat(alias, text);
        }
    }

    /** 辩论模式：校验模型数 → 对话流内实时渲染 */
    private void sendDebate(String topic) {
        if (debateAliases.size() < 2) {
            appendSystem("辩论模式需要 2~10 个模型：请点击「选择辩论模型…」勾选。");
            return;
        }
        setDebating(true);
        appendBubble("user", topic);
        int maxRounds = maxRoundsCombo.getValue() == null ? 4 : maxRoundsCombo.getValue();
        String modeLabel = discussionModeCombo.getValue() == null ? "辩论" : discussionModeCombo.getValue();
        String discussionMode = switch (modeLabel) {
            case "讨论" -> "discussion";
            case "头脑风暴" -> "brainstorm";
            default -> "debate";
        };
        appendSystem(modeLabel + "开始 · 主题：「" + topic + "」 · 参与模型：" + String.join("、", debateAliases)
                + (judgeAlias == null ? "" : " · 裁判：" + judgeAlias)
                + " · 最多 " + maxRounds + " 轮");

        Double budget = null;
        String budgetText = budgetField.getText();
        if (budgetText != null && !budgetText.isBlank()) {
            try {
                budget = Double.parseDouble(budgetText.trim());
            } catch (NumberFormatException e) {
                appendSystem("预算格式无效（已忽略）：" + budgetText);
            }
        }
        currentDebateSessionId = UUID.randomUUID().toString();
        DebateRequest request = new DebateRequest(currentDebateSessionId, topic, null,
                debateAliases, maxRounds, 300, budget, 0.92, 3, 0.10, judgeAlias, discussionMode);
        currentStream = debateEngine.stream(request)
                .doOnNext(event -> Platform.runLater(() -> handleDebateEvent(event)))
                .doOnError(error -> Platform.runLater(() -> {
                    appendSystem("辩论失败：" + error.getMessage());
                    setDebating(false);
                }))
                .doOnComplete(() -> Platform.runLater(() -> setDebating(false)))
                .subscribe(event -> {
                }, error -> {
                });
    }

    private void handleDebateEvent(DebateEvent event) {
        switch (event) {
            case DebateEvent.RoundStarted started -> {
                appendSystem("—— 第 " + started.round() + " 轮 ——");
                // 立即渲染各模型"思考中…"占位气泡（呼吸动画），消除等待期空白
                debateAliases.forEach(alias -> {
                    String key = alias + "#" + started.round();
                    ChatBubble placeholder = appendDebateBubble(alias,
                            "◆ " + alias + " · 第 " + started.round() + " 轮 · 思考中…");
                    placeholder.addStyle("bubble-thinking");
                    debatePlaceholders.put(key, placeholder);
                    javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(
                            javafx.util.Duration.millis(800), placeholder.node());
                    fade.setFromValue(1.0);
                    fade.setToValue(0.45);
                    fade.setAutoReverse(true);
                    fade.setCycleCount(javafx.animation.Animation.INDEFINITE);
                    fade.play();
                    thinkingAnimations.put(key, fade);
                });
            }
            case DebateEvent.ModelChunk chunk -> {
                // 逐 token 追加到对应占位气泡（打字机效果）
                String key = chunk.modelAlias() + "#" + chunk.round();
                ChatBubble placeholder = debatePlaceholders.get(key);
                if (placeholder == null) {
                    placeholder = appendDebateBubble(chunk.modelAlias(),
                            "◆ " + chunk.modelAlias() + " · 第 " + chunk.round() + " 轮 · 思考中…");
                    placeholder.addStyle("bubble-thinking");
                    debatePlaceholders.put(key, placeholder);
                }
                placeholder.append(chunk.chunk());
            }
            case DebateEvent.ModelText modelText -> {
                // 该模型本轮完成：停止动画、固化内容；失败内容标红
                String key = modelText.modelAlias() + "#" + modelText.round();
                ChatBubble placeholder = debatePlaceholders.remove(key);
                javafx.animation.FadeTransition fade = thinkingAnimations.remove(key);
                if (fade != null) {
                    fade.stop();
                }
                String full = modelText.text() == null ? "" : modelText.text();
                if (placeholder != null) {
                    placeholder.setText("◆ " + modelText.modelAlias() + " · 第 " + modelText.round()
                            + " 轮\n" + full);
                    placeholder.setOpacity(1.0);
                    placeholder.removeStyle("bubble-thinking");
                    placeholder.showActions(true);
                    if (full.contains("调用失败")) {
                        placeholder.addStyle("bubble-error");
                    }
                } else {
                    appendDebateBubble(modelText.modelAlias(), "◆ " + modelText.modelAlias() + " · 第 " + modelText.round()
                            + " 轮\n" + full);
                }
            }
            case DebateEvent.JudgeVerdict verdict -> appendWithAvatar("assistant",
                    "⚖ 裁判评语 · 第 " + verdict.round() + " 轮\n" + verdict.verdict(), "⚖", null);
            case DebateEvent.Completed completed -> {
                String reason = switch (completed.result().stopReason()) {
                    case MAX_ROUNDS -> "辩论结束（达到最大轮次）";
                    case INTERRUPTED -> "辩论已中断";
                    case TIMEOUT -> "辩论超时终止";
                    case CONSENSUS -> "辩论达成共识（语义相似度超过阈值，自动终止）";
                    case STALEMATE -> "观点僵持（连续多轮无新内容，集体哑火）";
                    case JUDGE_WINNER -> "裁判宣布获胜方："
                            + (completed.result().winnerAlias() == null ? "（未识别）" : completed.result().winnerAlias());
                    case JUDGE_DEADLOCK -> "裁判判定死锁（观点僵持）";
                    case COST_LIMIT -> "辩论成本超预算（已终止）";
                    default -> "辩论结束";
                };
                thinkingAnimations.values().forEach(javafx.animation.FadeTransition::stop);
                thinkingAnimations.clear();
                appendSystem(reason + " · 共 " + completed.result().rounds().size() + " 轮");
                refreshState();
                // 2.7 落库：异步归档（Session/Message/DebateRecord）
                if (debateRecordService != null && completed.result().rounds() != null
                        && !completed.result().rounds().isEmpty()) {
                    var result = completed.result();
                    Thread.ofVirtual().start(() -> {
                        try {
                            debateRecordService.save(result);
                        } catch (Exception e) {
                            // 落库失败不打断交互，日志可见
                        }
                    });
                }
            }
            case DebateEvent.Failed failed -> {
                thinkingAnimations.values().forEach(javafx.animation.FadeTransition::stop);
                thinkingAnimations.clear();
                appendSystem("辩论失败：" + failed.errorMessage());
            }
            default -> {
            }
        }
    }

    /** 辩论模型多选对话框（2~10；复选框交互，无需 Ctrl） */
    private void openModelPicker() {
        var models = modelGateway.availableModels().stream().map(model -> model.alias()).sorted().toList();
        if (models.isEmpty()) {
            appendSystem("尚无可用模型：请先到「⚙ 配置」添加。");
            return;
        }
        Map<String, CheckBox> boxes = new LinkedHashMap<>();
        VBox checkBoxes = new VBox(6);
        models.forEach(alias -> {
            CheckBox box = new CheckBox(alias);
            box.setSelected(debateAliases.contains(alias));
            boxes.put(alias, box);
            checkBoxes.getChildren().add(box);
        });

        // 裁判模型（可选：无 = 无裁判模式；复用已加载模型列表，role="judge"）
        ComboBox<String> judgeCombo = new ComboBox<>();
        judgeCombo.getItems().add("无（无裁判模式）");
        judgeCombo.getItems().addAll(models);
        judgeCombo.getSelectionModel().select(judgeAlias == null ? "无（无裁判模式）" : judgeAlias);
        Label judgeLabel = new Label("裁判模型（可选）");
        judgeLabel.getStyleClass().add("status");

        Button ok = new Button("确定");
        ok.getStyleClass().add("primary");
        Button cancel = new Button("取消");
        Label hint = new Label("勾选 2~10 个模型参与辩论（当前：" + debateAliases.size() + "）");
        hint.getStyleClass().add("status");
        HBox buttons = new HBox(8, ok, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(10));
        ScrollPane scroll = new ScrollPane(checkBoxes);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("chat-scroll");
        HBox judgeRow = new HBox(8, judgeLabel, judgeCombo);
        judgeRow.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, hint, scroll, judgeRow, buttons);
        box.setPadding(new Insets(12));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Stage picker = new Stage();
        picker.setTitle("选择辩论模型");
        picker.setScene(new Scene(box, 320, 420));
        picker.initModality(Modality.WINDOW_MODAL);
        picker.initOwner(modelCombo.getScene().getWindow());
        com.omniforge.ui.theme.ThemeManager.attach(picker.getScene());
        ok.setOnAction(event -> {
            List<String> selected = boxes.entrySet().stream()
                    .filter(entry -> entry.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .toList();
            int maxModels = licenseService != null ? licenseService.maxDebateModels() : 10;
            if (selected.size() < 2 || selected.size() > maxModels) {
                hint.setText("须选择 2~" + maxModels + " 个模型（" + selected.size() + " 个已选；"
                        + (maxModels == 3 ? "社区版上限 3，专业版 10" : "专业版上限 10") + "）");
                return;
            }
            debateAliases = selected;
            String judgeSelection = judgeCombo.getSelectionModel().getSelectedItem();
            judgeAlias = "无（无裁判模式）".equals(judgeSelection) || judgeSelection == null
                    ? null : judgeSelection;
            modelPickerButton.setText("辩论模型：" + String.join(", ", selected)
                    + (judgeAlias == null ? "" : "｜裁判：" + judgeAlias));
            picker.close();
        });
        cancel.setOnAction(event -> picker.close());
        picker.show();
    }

    private void startDirectChat(String alias, String text) {
        // 多轮上下文（Phase 4 ContextManager）：裁剪后历史经网关中性消息注入
        List<GatewayHistoryMessage> history = contextHistory(text, alias);
        GatewayRequest request = new GatewayRequest(alias, null, text, null, null, history, osIdentity());
        currentStream = modelGateway.streamText(request)
                .doOnNext(chunk -> Platform.runLater(() -> appendChunk(chunk)))
                .doOnError(error -> Platform.runLater(() -> {
                    appendChunk("（调用失败：" + error.getMessage() + "）");
                    markAssistantError();
                    finishSend();
                }))
                .doOnComplete(() -> Platform.runLater(() -> {
                    commitTurn(text, alias);
                    finishSend();
                }))
                .subscribe(chunk -> {
                }, error -> {
                });
        singleRunActive = true;
        agentModeRun = false;
        armStop();
    }

    private void startAgentChat(String alias, String text) {
        var tools = toolRegistry.all().stream().toList();
        java.util.List<org.springframework.ai.chat.messages.Message> history = contextManager != null
                ? contextManager.buildHistory(currentSessionKey(), text, contextWindow(alias))
                : java.util.List.of();
        AgentRunRequest request = AgentRunRequest.of(
                UUID.randomUUID().toString(), UI_SESSION_ID, alias, null, text, tools, history, osIdentity());
        currentStream = agentEngine.stream(request)
                .doOnNext(event -> Platform.runLater(() -> handleAgentEvent(event)))
                .doOnError(error -> Platform.runLater(() -> {
                    appendChunk("（Agent 调用失败：" + error.getMessage() + "）");
                    markAssistantError();
                    finishSend();
                }))
                .doOnComplete(() -> Platform.runLater(() -> {
                    commitTurn(text, alias);
                    finishSend();
                }))
                .subscribe(event -> {
                }, error -> {
                });
        singleRunActive = true;
        agentModeRun = true;
        agentRunId = request.runId();
        armStop();
    }

    /** 直连对话历史：ContextManager 裁剪结果 → 网关中性消息（system = 摘要条目） */
    private List<GatewayHistoryMessage> contextHistory(String userText, String alias) {
        if (contextManager == null) {
            return List.of();
        }
        return contextManager.trimmedHistory(currentSessionKey(), userText, contextWindow(alias)).stream()
                .map(entry -> new GatewayHistoryMessage(switch (entry.role()) {
                    case USER -> GatewayHistoryRole.USER;
                    case ASSISTANT -> GatewayHistoryRole.ASSISTANT;
                    case SYSTEM -> GatewayHistoryRole.SYSTEM;
                }, entry.content()))
                .toList();
    }

    /** 路由身份键：GUI 直连/Agent 按 OS 用户名（与审计口径一致，Batch2 权限模型） */
    private static String osIdentity() {
        return "os:" + System.getProperty("user.name", "local");
    }

    /** 模型上下文窗口 token（未配置或 alias 为 null 返回 null，裁剪仅按全局预算） */
    private Integer contextWindow(String alias) {
        if (modelGateway == null || alias == null) {
            return null;
        }
        return modelGateway.availableModels().stream()
                .filter(model -> alias.equals(model.alias()))
                .map(model -> model.contextWindowTokens())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** 回复成功后把本轮 user/assistant 成对追加进会话上下文（失败轮次不追加，保持交替结构） */
    private void commitTurn(String userText, String alias) {
        String assistantText = pendingTurnAssistant != null ? pendingTurnAssistant.getText() : "";
        if (contextManager != null) {
            contextManager.append(currentSessionKey(), ContextRole.USER, userText);
            contextManager.append(currentSessionKey(), ContextRole.ASSISTANT, assistantText);
        }
        // 历史会话落库（问题一）：首轮自动创建会话（标题 = 首条消息前 30 字），刷新侧栏
        if (chatSessionService != null) {
            try {
                String sessionId = chatSessionService.appendTurn(
                        currentChatSessionId, userText, assistantText, alias);
                if (currentChatSessionId == null) {
                    currentChatSessionId = sessionId;
                }
                refreshHistoryTree();
            } catch (Exception e) {
                log.warn("对话落库失败（不影响交互）：{}", e.getMessage());
            }
        }
    }

    /**
     * HITL 透明性（TOOL_CONFIRMATION §3.3）：判断工具本次调用是否需要人工确认。
     * 按参数操作级判定（file_read_write 仅 write/append/delete 需确认）。
     */
    private boolean toolNeedsConfirmation(String toolName, String arguments) {
        if (toolRegistry == null) {
            return false;
        }
        for (com.omniforge.common.spi.Tool tool : toolRegistry.all()) {
            if (tool.spec().name().equals(toolName)) {
                return tool.requiresConfirmation(parseToolArguments(arguments));
            }
        }
        return false;
    }

    /** 步骤卡片展示用：参数 JSON 转 Map（解析失败给空 Map——工具级标志仍可生效） */
    private static java.util.Map<String, Object> parseToolArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return java.util.Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(arguments,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    private void handleAgentEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.TextChunk chunk -> {
                lastThinkingText = chunk.text();
                // Agent 模式：模型的“思考”文本不进回复气泡（否则刷成思维墙、答案难找）；
                // 只留作下一步骤卡片的 🧠 思考。最终答案在 Completed 统一落到气泡末尾。
                if (!agentModeRun) {
                    appendChunk(chunk.text());
                }
            }
            case AgentEvent.ToolCallStarted started -> {
                pendingStepCard = new StepCard(lastThinkingText, started.toolName(), started.arguments());
                // HITL 透明性（TOOL_CONFIRMATION §3.3）：工具需人工确认 → 观察行提示等待用户
                if (toolNeedsConfirmation(started.toolName(), started.arguments())) {
                    pendingStepCard.awaitingApproval();
                }
            }
            case AgentEvent.ToolCallFinished finished -> {
                if (pendingStepCard != null) {
                    pendingStepCard.finish(finished.record().result(),
                            finished.record().status() == com.omniforge.common.spi.ToolResult.ToolStatus.SUCCESS);
                    pendingStepCard = null;
                }
            }
            case AgentEvent.Completed completed -> {
                if (agentModeRun
                        && completed.result().stopReason() == com.omniforge.core.agent.StopReason.COMPLETED
                        && currentAssistantBubble != null
                        && completed.result().text() != null && !completed.result().text().isBlank()) {
                    // 最终答案放入回复气泡并移到最底：阅读顺序 = 思考/工具 → 结论
                    currentAssistantBubble.setText(completed.result().text());
                    currentAssistantBubble.moveToBottom();
                }
                if (completed.result().stopReason() != com.omniforge.core.agent.StopReason.COMPLETED) {
                    appendSystem("运行终止：" + completed.result().stopReason()
                            + (completed.result().errorMessage() == null ? ""
                            : "（" + completed.result().errorMessage() + "）"));
                }
            }
            case AgentEvent.Failed failed -> {
                if (currentAssistantBubble != null) {
                    appendChunk("（失败：" + failed.errorMessage() + "）");
                    markAssistantError();
                } else {
                    appendSystem("（失败：" + failed.errorMessage() + "）");
                }
            }
            default -> {
            }
        }
    }

    private final class StepCard {

        private final VBox root;
        private final Label observe;
        private final ProgressIndicator spinner;
        private final Button stopButton;

        StepCard(String thinking, String toolName, String arguments) {
            Label think = stepRow("🧠 思考", truncate(thinking, TEXT_TRUNCATE));
            Label action = stepRow("⚡ 行动", toolName
                    + (arguments == null || arguments.isBlank() ? "" : "（" + truncate(arguments, TEXT_TRUNCATE) + "）"));
            // 观察行：加载转圈（工具执行中） + 结果文本 + 停止当前工具入口
            spinner = new ProgressIndicator(-1); // -1 = 不确定进度（旋转加载）
            spinner.setPrefSize(14, 14);
            spinner.setMaxSize(14, 14);
            observe = new Label("执行中…");
            observe.setWrapText(true);
            HBox.setHgrow(observe, Priority.ALWAYS);
            stopButton = new Button("停止本工具");
            stopButton.getStyleClass().add("danger");
            stopButton.setTooltip(new javafx.scene.control.Tooltip(
                    "请求终止本次运行：当前工具执行到结束点后停止（长时工具请用其自身超时兜底）"));
            stopButton.setOnAction(event -> stopToolRequest());
            HBox observeRow = new HBox(6, new Label("👁 观察"), spinner, observe, stopButton);
            observeRow.setAlignment(Pos.CENTER_LEFT);
            root = new VBox(4, think, action, observeRow);
            root.getStyleClass().addAll("step-card", "step-running");
            root.setMaxWidth(BUBBLE_MAX_WIDTH);
            autoScrollToBottom = true; // 步骤卡出现即跟随底部
            chatBox.getChildren().add(root);
            scrollToBottom();
        }

        /** HITL：工具需人工确认时观察行显示等待状态（TOOL_CONFIRMATION §3.3 透明性） */
        void awaitingApproval() {
            observe.setText("⏳ 等待用户确认…");
        }

        void finish(String result, boolean success) {
            spinner.setVisible(false);
            spinner.setManaged(false);
            stopButton.setVisible(false);
            stopButton.setManaged(false);
            observe.setText(result == null || result.isBlank() ? "（空）"
                    : truncate(result, TEXT_TRUNCATE));
            root.getStyleClass().remove("step-running");
            if (!success) {
                root.getStyleClass().add("step-error");
            }
        }

        /** 整体运行被用户停止：清除仍在旋转的加载态 */
        void abort() {
            spinner.setVisible(false);
            spinner.setManaged(false);
            stopButton.setVisible(false);
            stopButton.setManaged(false);
            observe.setText("已停止（中断）");
            root.getStyleClass().remove("step-running");
        }

        /** 运行中点击「停止本工具」：请求引擎中断（工具阻塞时在结束点后终止），提示即时可见 */
        private void stopToolRequest() {
            if (stopButton.isDisabled()) {
                return;
            }
            stopButton.setDisable(true);
            observe.setText("正在停止…（当前工具调用结束后终止）");
            if (agentModeRun && agentRunId != null && agentEngine != null) {
                agentEngine.cancel(agentRunId);
            }
        }

        private Label stepRow(String labelText, String value) {
            Label row = new Label(labelText + "  " + value);
            row.setWrapText(true);
            return row;
        }
    }

    /** 追加对话气泡（TextFlow：可选中复制 + 右键菜单 + Delete 删除） */
    private ChatBubble appendBubble(String role, String text) {
        hideEmptyState();
        return new ChatBubble(role, text, null, null, null);
    }

    /** 助手气泡（带重新生成回调：操作行含 🔄） */
    private ChatBubble appendAssistant(String text, java.util.function.Consumer<ChatBubble> onRegenerate) {
        hideEmptyState();
        return new ChatBubble("assistant", text, onRegenerate, null, null);
    }

    /** 带自定义头像的气泡（辩论模型色点 / 裁判 ⚖） */
    private ChatBubble appendWithAvatar(String role, String text, String avatarText, String avatarColor) {
        hideEmptyState();
        return new ChatBubble(role, text, null, avatarText, avatarColor);
    }

    /** 辩论气泡：头像为模型专属颜色圆点（同一别名颜色恒定，P0 二轮） */
    private ChatBubble appendDebateBubble(String alias, String text) {
        return appendWithAvatar("assistant", text, "●", debateColor(alias));
    }

    /** 辩论模型颜色盘：别名哈希取色，恒定映射 */
    private static final String[] DEBATE_COLORS =
            {"#2563EB", "#F59E0B", "#10B981", "#EF4444", "#8B5CF6", "#EC4899"};

    private static String debateColor(String alias) {
        return DEBATE_COLORS[Math.floorMod(alias.hashCode(), DEBATE_COLORS.length)];
    }

    private void appendSystem(String text) {
        hideEmptyState();
        new ChatBubble("system", text, null, null, null);
    }

    private void hideEmptyState() {
        if (chatBox.getChildren().contains(emptyState)) {
            chatBox.getChildren().remove(emptyState);
        }
    }

    /** 请求贴底：同一脉冲内多次请求只排一次队（多来源调用/高度变化不造成上下震荡） */
    private void requestScrollBottom() {
        if (!autoScrollToBottom || chatScroll == null || scrollScheduled) {
            return;
        }
        scrollScheduled = true;
        Platform.runLater(() -> {
            scrollScheduled = false;
            if (autoScrollToBottom && chatScroll != null) {
                chatScroll.setVvalue(1.0);
            }
        });
    }

    private void scrollToBottom() {
        requestScrollBottom();
    }

    /**
     * 消息气泡（P0-2 修复版，DeepSeek 网页端风格）：
     * 助手消息 = 左侧 🤖 头像 + 纯文本（无卡片底）+ 下方操作按钮行（📋 复制 · 🔄 重新生成 · 🗑 删除）；
     * 用户消息 = 品牌色气泡 + 右侧 👤 头像，悬停左侧出现 📋 复制小按钮；
     * 全部消息支持拖选部分内容 Ctrl+C、右键菜单、Delete 删除，高度随内容自动伸缩；
     * 每条消息右下角带时间戳（P0 二轮）。
     */
    private final class ChatBubble {

        private final VBox container;
        private final HBox row;
        private final TextArea area;
        private final Button copyButton;
        private final HBox actionsRow;
        private final String role;
        private final java.util.function.Consumer<ChatBubble> onRegenerate;
        private final Label timeLabel;
        /** 生成中指示动画（P2：助手气泡流式期间循环加点，完成后停止） */
        private final javafx.animation.Timeline generatingTimer;

        ChatBubble(String role, String content, java.util.function.Consumer<ChatBubble> onRegenerate,
                   String avatarText, String avatarColor) {
            this.role = role;
            this.onRegenerate = onRegenerate;
            area = new TextArea(content == null ? "" : content);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(1);
            area.setPrefColumnCount(1);
            area.getStyleClass().addAll("bubble", "bubble-" + role);
            area.setFocusTraversable(true);
            // 助手消息占满整行（纯文本布局）；用户/系统气泡随内容伸缩
            if ("assistant".equals(role)) {
                area.setMaxWidth(Double.MAX_VALUE);
            } else {
                area.setMaxWidth(BUBBLE_MAX_WIDTH);
            }
            // 自动尺寸：文本/宽度变化时按内容重算高度（用户/系统还重算宽度贴合内容）
            area.textProperty().addListener((obs, old, text) -> updateSize());
            area.widthProperty().addListener((obs, old, width) -> updateSize());

            ContextMenu menu = new ContextMenu();
            MenuItem copyItem = new MenuItem("复制");
            copyItem.setOnAction(event -> copyText());
            MenuItem deleteItem = new MenuItem("删除此消息");
            deleteItem.setOnAction(event -> removeBubble(this));
            MenuItem deleteAllItem = new MenuItem("删除全部");
            deleteAllItem.setOnAction(event -> clearAllMessages());
            menu.getItems().addAll(copyItem, deleteItem, deleteAllItem);
            com.omniforge.ui.theme.ThemeManager.attach(menu); // 右键菜单随主题配色
            area.setContextMenu(menu);

            // 聚焦后 Delete 键删除
            area.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.DELETE) {
                    removeBubble(this);
                    event.consume();
                }
            });

            // 用户气泡：悬停左侧显示小复制按钮；助手/系统：复制在操作行/右键菜单
            copyButton = new Button("📋");
            copyButton.getStyleClass().add("bubble-copy");
            copyButton.setTooltip(new javafx.scene.control.Tooltip("复制此消息"));
            copyButton.setVisible(false);
            copyButton.setManaged(false);
            copyButton.setOnAction(event -> copyText());

            // 头像标识（P0 二轮）：助手左侧 🤖（辩论为模型色点）、用户右侧 👤、系统无
            if ("user".equals(role)) {
                Label avatar = avatarLabel("👤", null);
                row = new HBox(6, copyButton, area, avatar);
                row.setAlignment(Pos.CENTER_RIGHT);
                row.setOnMouseEntered(event -> showCopyButton(true));
                row.setOnMouseExited(event -> showCopyButton(false));
            } else {
                Label avatar = "assistant".equals(role)
                        ? avatarLabel(avatarText == null || avatarText.isBlank() ? "🤖" : avatarText, avatarColor)
                        : null;
                row = avatar == null
                        ? new HBox(area)
                        : new HBox(8, avatar, area);
                row.setAlignment("system".equals(role) ? Pos.CENTER : Pos.CENTER_LEFT);
                if ("assistant".equals(role)) {
                    HBox.setHgrow(area, Priority.ALWAYS);
                }
            }
            row.setMaxWidth(Double.MAX_VALUE);

            // 助手消息操作按钮行（生成完成后显示）
            actionsRow = new HBox(4);
            actionsRow.getStyleClass().add("bubble-actions");
            Button copyAction = actionButton("📋 复制", this::copyText);
            Button deleteAction = actionButton("🗑 删除", () -> removeBubble(this));
            actionsRow.getChildren().addAll(copyAction);
            if (onRegenerate != null) {
                Button regenAction = actionButton("🔄 重新生成",
                        () -> onRegenerate.accept(ChatBubble.this));
                actionsRow.getChildren().add(regenAction);
            }
            actionsRow.getChildren().add(deleteAction);
            actionsRow.setVisible(false);
            actionsRow.setManaged(false);

            // 时间戳（P0 二轮）：右下角小字；助手气泡流式期间显示"生成中"动画（P2），完成时更新为完成时间
            timeLabel = new Label(nowTime());
            timeLabel.getStyleClass().add("bubble-time");
            HBox timeRow = new HBox(timeLabel);
            timeRow.setAlignment(Pos.CENTER_RIGHT);
            timeRow.getStyleClass().add("bubble-time-row");

            generatingTimer = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(400), event -> {
                        String current = timeLabel.getText();
                        int dots = current == null || !current.startsWith("生成中")
                                ? 0 : current.length() - "生成中".length();
                        dots = dots >= 3 ? 1 : dots + 1;
                        timeLabel.setText("生成中" + "·".repeat(dots));
                    }));
            generatingTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
            if ("assistant".equals(role)) {
                timeLabel.setText("生成中");
                generatingTimer.play();
            }

            if ("assistant".equals(role) || "user".equals(role)) {
                container = new VBox(3, row, actionsRow, timeRow);
            } else {
                container = new VBox(row);
            }
            container.setMaxWidth(Double.MAX_VALUE);
            // 用户消息：操作行（📋 复制 · 🗑 删除）默认隐藏，悬停消息块时显示——
            // 既保证自己的消息有可见删除入口，又不让单行消息常占高（P1-3 UI 反馈）
            if ("user".equals(role)) {
                container.setOnMouseEntered(event -> setUserActions(true));
                container.setOnMouseExited(event -> setUserActions(false));
            }
            autoScrollToBottom = true; // 新消息出现即贴底（用户上翻阅读时仍被新内容拉回——符合对话习惯）
            chatBox.getChildren().add(container);
            scrollToBottom();
        }

        private static Label avatarLabel(String text, String color) {
            Label label = new Label(text);
            label.getStyleClass().add("bubble-avatar");
            if (color != null) {
                label.setStyle("-fx-text-fill: " + color + ";");
            }
            return label;
        }

        private static String nowTime() {
            return java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        }

        private Button actionButton(String label, Runnable action) {
            Button button = new Button(label);
            button.getStyleClass().add("bubble-action");
            button.setOnAction(event -> action.run());
            return button;
        }

        String getText() {
            return area.getText();
        }

        void setText(String value) {
            area.setText(value == null ? "" : value);
        }

        void append(String chunk) {
            area.setText(area.getText() + chunk);
        }

        void addStyle(String styleClass) {
            area.getStyleClass().add(styleClass);
        }

        void removeStyle(String styleClass) {
            area.getStyleClass().remove(styleClass);
        }

        void setOpacity(double value) {
            area.setOpacity(value);
        }

        javafx.scene.Node node() {
            return area;
        }

        /** 把本消息容器移到对话流最底（Agent 完成后把最终答案排到步骤卡之后，思考→工具→结论阅读序） */
        void moveToBottom() {
            chatBox.getChildren().remove(container);
            chatBox.getChildren().add(container);
            scrollToBottom();
        }

        /** 生成完成后显示操作按钮行（流式期间隐藏）；显示时更新完成时间戳 */
        void showActions(boolean show) {
            actionsRow.setVisible(show);
            actionsRow.setManaged(show);
            if (show) {
                generatingTimer.stop();
                timeLabel.setText(nowTime());
            } else if ("assistant".equals(role)) {
                timeLabel.setText("生成中");
                generatingTimer.play();
            }
        }

        /** 复制：优先当前选区（部分内容），无选区复制全文 */
        private void copyText() {
            String selected = area.getSelectedText();
            String payload = selected == null || selected.isEmpty() ? area.getText() : selected;
            ClipboardContent content = new ClipboardContent();
            content.putString(payload);
            Clipboard.getSystemClipboard().setContent(content);
        }

        private void showCopyButton(boolean show) {
            copyButton.setVisible(show);
            copyButton.setManaged(show);
        }

        /** 用户消息悬停：复制小钮 + 操作行（复制/删除）一起显隐（常显会让单行气泡过高） */
        private void setUserActions(boolean show) {
            copyButton.setVisible(show);
            copyButton.setManaged(show);
            actionsRow.setVisible(show);
            actionsRow.setManaged(show);
        }

        /** 按内容自动调整高度（用户/系统气泡同时贴合内容宽度） */
        private void updateSize() {
            double width = area.getWidth();
            if (width <= 0) {
                return;
            }
            double padH = area.getPadding().getLeft() + area.getPadding().getRight();
            double padV = area.getPadding().getTop() + area.getPadding().getBottom();
            Text helper = new Text(area.getText());
            helper.setFont(area.getFont() == null ? javafx.scene.text.Font.font(13) : area.getFont());
            if ("assistant".equals(role)) {
                // 助手占满整行：按实际可用宽度计算换行高度
                double wrapWidth = Math.max(60, width - padH - 6);
                helper.setWrappingWidth(wrapWidth);
                area.setPrefHeight(helper.getLayoutBounds().getHeight() * 1.1 + padV + 10);
            } else {
                // 用户/系统气泡：宽度贴合未换行内容（上限 640），高度按最终气泡宽度换行计算。
                // 关键：内容宽度必须在未换行状态下测量——若按当前（可能很窄的）宽度换行后测量，
                // 会得到逐字竖排的窄条宽度并锁定（P0 变形根因：窄宽度→逐字换行→更窄的死锁）。
                // 预留足够松弛量：避免「内容刚好逼近换行宽度时把末尾 1~2 字挤到下一行」的临界换行。
                double contentWidth = helper.getLayoutBounds().getWidth();
                double prefW = Math.max(96, Math.min(BUBBLE_MAX_WIDTH, contentWidth + padH + 22));
                area.setPrefWidth(prefW);
                helper.setWrappingWidth(prefW - padH - 8);
                area.setPrefHeight(helper.getLayoutBounds().getHeight() * 1.1 + padV + 10);
            }
            // 布局后按真实内容区高度修正：helper 估算受字体指标偏差影响，
            // 低估即触发 TextArea 滚动条（P0 修复：滚动条彻底不出现）。
            // 防陈旧测量：布局瞬态期内容区可能按旧窄宽度逐字换行（高度虚高数倍），
            // 仅接受与当前估算同量级的实测值，虚高值丢弃（下一轮文本/宽度变化会重测）。
            double estimate = area.getPrefHeight();
            Platform.runLater(() -> {
                javafx.scene.Node content = area.lookup(".content");
                if (content != null) {
                    double real = content.getBoundsInLocal().getHeight();
                    if (real > 0 && real < estimate * 1.8) {
                        area.setPrefHeight(real + padV + 12);
                    }
                }
            });
        }
    }

    /** 删除单个气泡（连带清理流式/辩论引用与动画；同步清空上下文记忆避免幽灵历史） */
    private void removeBubble(ChatBubble bubble) {
        if (currentAssistantBubble == bubble) {
            currentAssistantBubble = null;
        }
        if (pendingTurnAssistant == bubble) {
            pendingTurnAssistant = null;
        }
        String placeholderKey = null;
        for (var entry : debatePlaceholders.entrySet()) {
            if (entry.getValue() == bubble) {
                placeholderKey = entry.getKey();
                break;
            }
        }
        if (placeholderKey != null) {
            debatePlaceholders.remove(placeholderKey);
            var fade = thinkingAnimations.remove(placeholderKey);
            if (fade != null) {
                fade.stop();
            }
        }
        chatBox.getChildren().remove(bubble.container);
        if (chatBox.getChildren().isEmpty()) {
            chatBox.getChildren().add(emptyState);
        }
        clearContextMemory();
    }

    /** 删除全部消息（恢复空状态；清空流式/辩论引用与动画） */
    private void clearAllMessages() {
        thinkingAnimations.values().forEach(javafx.animation.FadeTransition::stop);
        thinkingAnimations.clear();
        debatePlaceholders.clear();
        currentAssistantBubble = null;
        pendingTurnAssistant = null;
        chatBox.getChildren().clear();
        chatBox.getChildren().add(emptyState);
        clearContextMemory();
    }

    /** 删除消息后同步清空上下文记忆：历史为轮对结构无法精确单条删除，整体重置最可预期（P0-2） */
    private void clearContextMemory() {
        if (contextManager != null) {
            contextManager.clear(currentSessionKey());
            appendSystem("上下文记忆已同步清空");
        }
    }

    private void appendChunk(String chunk) {
        if (currentAssistantBubble != null) {
            currentAssistantBubble.append(chunk);
            scrollToBottom();
        }
    }

    private void markAssistantError() {
        if (currentAssistantBubble != null) {
            currentAssistantBubble.addStyle("bubble-error");
        }
    }

    private void finishSend() {
        if (currentAssistantBubble != null) {
            currentAssistantBubble.showActions(true); // 生成完成：显示 📋/🔄/🗑 操作行
        }
        pendingTurnAssistant = null;
        setSending(false);
        refreshState();
    }

    /** 单模型（直连/Agent）运行中：发送钮变为可点的「停止」（danger），用户可手动中止 */
    private void armStop() {
        sendButton.setDisable(false);
        sendButton.setText("停止");
        sendButton.getStyleClass().remove("primary");
        sendButton.getStyleClass().add("danger");
    }

    private void setSending(boolean sending) {
        sendButton.setDisable(sending);
        sendButton.setText(sending ? "发送中…" : "发送");
        if (!sending) {
            currentStream = null;
            singleRunActive = false;
            agentModeRun = false;
            agentRunId = null;
            sendButton.getStyleClass().remove("danger");
            sendButton.getStyleClass().add("primary");
        }
    }

    private void setDebating(boolean running) {
        debating = running;
        if (running) {
            sendButton.setText("停止辩论");
            sendButton.getStyleClass().remove("primary");
            sendButton.getStyleClass().add("danger");
        } else {
            sendButton.setText("发送");
            sendButton.getStyleClass().remove("danger");
            sendButton.getStyleClass().add("primary");
            currentStream = null;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    @Override
    public void stop() {
        if (currentStream != null) {
            currentStream.dispose();
        }
    }
}
