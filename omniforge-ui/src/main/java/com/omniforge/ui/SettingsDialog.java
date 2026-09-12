package com.omniforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.omniforge.core.gateway.GatewayProperties;
import com.omniforge.core.gateway.ModelConfig;
import com.omniforge.core.gateway.ModelConfigLoader;
import com.omniforge.core.gateway.ModelGateway;
import com.omniforge.core.gateway.ModelGatewayConfig;
import com.omniforge.core.gateway.ProviderConfig;
import com.omniforge.core.gateway.router.RoutingConfig;
import com.omniforge.core.gateway.router.RoutingConfigStore;
import com.omniforge.core.gateway.router.RoutingRule;
import com.omniforge.core.gateway.router.RoutingStrategy;
import com.omniforge.core.gateway.router.RoutingStrategyType;
import com.omniforge.core.context.ContextSettings;
import com.omniforge.core.context.ContextSettingsHolder;
import com.omniforge.core.context.ContextSettingsStore;
import com.omniforge.core.context.TrimStrategy;
import com.omniforge.core.retention.BackupService;
import com.omniforge.core.retention.DataRetentionService;
import com.omniforge.core.retention.RetentionSettings;
import com.omniforge.core.retention.RetentionSettingsHolder;
import com.omniforge.core.retention.RetentionSettingsStore;
import com.omniforge.tools.ToolsProperties;
import com.omniforge.tools.ToolsSettings;
import com.omniforge.tools.ToolsSettingsHolder;
import com.omniforge.tools.ToolsSettingsStore;
import com.omniforge.tools.mcp.McpServerConfig;
import com.omniforge.tools.mcp.McpSettings;
import com.omniforge.tools.mcp.McpSettingsHolder;
import com.omniforge.tools.mcp.McpSettingsStore;
import com.omniforge.gateway.qq.QqSettings;
import com.omniforge.gateway.qq.QqSettingsStore;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 配置中心（v5.3：全部配置 GUI 化，用户不直接接触 models.yml）。
 *
 * <p>设计：提供商/模型以卡片编辑——能选择的用下拉（提供商类型、密钥来源、
 * 模型 ID 按厂商给建议值），不能选择的给示例与帮助文字（base-url、密钥、定价）；
 * 一键预设模板（DeepSeek/OpenAI/Moonshot/通义千问）。保存时序列化为 models.yml
 * （内部存储格式）并立即热重载。</p>
 */
final class SettingsDialog {

    /** 密钥来源三选一 */
    private static final String KEY_ENV = "环境变量（推荐）";
    private static final String KEY_STORE = "加密存储";
    private static final String KEY_PLAIN = "明文（仅个人测试）";

    /** 各厂商常用模型建议值（可编辑，也可手输自定义模型 ID） */
    private static final Map<String, List<String>> MODEL_SUGGESTIONS = Map.of(
            "dashscope", List.of("qwen-plus", "qwen-max", "qwen-turbo", "qwen-long", "qwen-flash"),
            "openai", List.of("gpt-4o", "gpt-4o-mini", "gpt-3.5-turbo"),
            "deepseek", List.of("deepseek-chat", "deepseek-reasoner"),
            "moonshot", List.of("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"));

    // ---------- 配置中心模块（Batch1 模块化：左侧栏导航 + 分模块保存） ----------

    private static final String MODULE_MODELS = "模型管理";
    private static final String MODULE_ROUTING = "路由规则";
    private static final String MODULE_MESSAGING = "消息接入";
    private static final String MODULE_CONTEXT = "上下文管理";
    private static final String MODULE_TOOLS = "工具配置";
    private static final String MODULE_MCP = "MCP 服务器";
    private static final String MODULE_LICENSE = "License 授权";
    private static final String MODULE_DATA = "数据管理";

    /** 侧栏模块顺序（模型后即路由规则） */
    private static final List<String> MODULES = List.of(
            MODULE_MODELS, MODULE_ROUTING, MODULE_MESSAGING, MODULE_CONTEXT, MODULE_TOOLS,
            MODULE_MCP, MODULE_LICENSE, MODULE_DATA);

    /** 各模块保存后的生效说明（模块按钮行下展示 + 保存成功消息口径统一） */
    private static final String EFFECT_MODELS = "保存后立即热重载（对话/辩论引擎按新模型列表生效，无需重启）。";
    private static final String EFFECT_ROUTING = "保存后立即生效（无需重启）。";
    private static final String EFFECT_MESSAGING = "保存后：连接参数立即热生效；启用/关闭通道需重启应用。";
    private static final String EFFECT_CONTEXT = "保存后立即生效（无需重启）。";
    private static final String EFFECT_TOOLS = "保存后立即生效（无需重启）。";
    private static final String EFFECT_MCP = "保存后立即生效：客户端重建服务器连接（无需重启）。";
    private static final String EFFECT_LICENSE = "激活后即时生效（无需重启）。";
    private static final String EFFECT_DATA = "保存后立即生效（无需重启）；每日后台扫描按新设置执行。";

    private static final String NAPCAT_HINT = "NapCat 通道为技术预览：请编辑 application.yml 的 "
            + "omniforge.im.napcat.* 后重启应用生效（配置中心暂不提供 GUI）。";

    private final ModelGateway modelGateway;
    private final GatewayProperties properties;
    private final Runnable onSaved;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final VBox providerBox = new VBox(10);
    private final VBox modelBox = new VBox(10);
    private final List<ProviderCard> providerCards = new ArrayList<>();
    private final List<ModelCard> modelCards = new ArrayList<>();
    private final ComboBox<String> defaultModelCombo = new ComboBox<>();
    private final Label status = new Label();
    private Stage dialogStage;
    private final CheckBox shellToggle = new CheckBox("shell_executor（危险操作，默认关闭）");
    private final CheckBox pythonToggle = new CheckBox("python_interpreter");
    /** U11：危险操作人工确认开关（关 = 不弹确认框直接执行，用户自担风险） */
    private final CheckBox confirmationToggle = new CheckBox("危险操作人工确认（HITL）");
    // 文件沙箱根目录（2026-09：支持多个授权目录；空列表 = 仅默认工作区）
    private final VBox workspaceRootsBox = new VBox(6);
    private final List<WorkspaceRootRow> workspaceRootRows = new ArrayList<>();
    private final Label defaultWorkspaceHint = new Label();
    private final TextField licenseKeyField = new TextField();
    private final Label licenseStatus = new Label();
    private final TextField licenseFingerprint = new TextField();
    private com.omniforge.core.persistence.service.LicenseService licenseService;
    private final CheckBox contextToggle = new CheckBox("启用多轮上下文记忆（ContextManager）");
    private final ComboBox<String> contextStrategyCombo = new ComboBox<>();
    private final TextField contextBudgetField = new TextField();
    private final TextField contextKeepRecentField = new TextField();
    private final ComboBox<String> contextSummaryModelCombo = new ComboBox<>();
    private final VBox mcpBox = new VBox(10);
    private final List<McpServerCard> mcpCards = new ArrayList<>();
    private ToolsSettingsHolder settingsHolder;
    private Path toolsFile;
    private ContextSettingsHolder contextHolder;
    private Path contextFile;
    private McpSettingsHolder mcpHolder;
    private Path mcpFile;
    // QQ 官方机器人（2026-09：NapCat 共存，官方方案为主推）
    private final CheckBox qqEnabledToggle = new CheckBox("启用 QQ 官方机器人");
    private final TextField qqAppIdField = new TextField();
    private final PasswordField qqSecretField = new PasswordField();
    private final ComboBox<String> qqEnvCombo = new ComboBox<>();
    private final CheckBox qqWebhookToggle = new CheckBox("启用 Webhook 回调（公网场景；默认走 WebSocket）");
    private final TextField qqWebhookPortField = new TextField();
    private QqSettingsStore qqStore;
    private Path qqFile;
    // 路由规则（Batch2：层级 1~5 + 身份规则）
    private final CheckBox routingEnabledToggle = new CheckBox("启用智能路由（需专业版 Pro）");
    private final TextField routingThresholdField = new TextField();
    private final TextField routingWeightTokenField = new TextField();
    private final TextField routingWeightKeywordField = new TextField();
    private final TextField routingWeightEmbeddingField = new TextField();
    private final TextArea routingKeywordsArea = new TextArea();
    private final TextArea routingExemplarsArea = new TextArea();
    private final TextField[] routingTierNameFields = {
            new TextField(), new TextField(), new TextField(), new TextField(), new TextField()};
    private final ComboBox<String> routingStrategyCombo = new ComboBox<>();
    private final TextField routingParamField = new TextField();
    private final List<RoutingRuleRow> routingRuleRows = new ArrayList<>();
    private final VBox routingRulesBox = new VBox(6);
    // 数据保留与备份（A 级批次：DATA_RETENTION A3/A4）
    private final CheckBox retentionEnabledToggle = new CheckBox("启用数据保留策略");
    private final TextField retentionChatDaysField = new TextField();
    private final TextField retentionLogDaysField = new TextField();
    private final TextField retentionAuditDaysField = new TextField();
    private RetentionSettingsHolder retentionHolder;
    private DataRetentionService retentionService;
    private BackupService backupService;
    private Path retentionFile;
    /** 打开时定位的模块（showLicense 用）；null = 默认选中第一个模块 */
    private String pendingModule;

    SettingsDialog(ModelGateway modelGateway, GatewayProperties properties, Runnable onSaved) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.onSaved = Objects.requireNonNull(onSaved, "onSaved");
    }

    /** 打开配置中心并定位 License 授权模块（运营中心「前往激活 Pro」入口） */
    void showLicense(Window owner) {
        this.pendingModule = MODULE_LICENSE;
        show(owner);
    }

    void show(Window owner) {
        ModelGatewayConfig config;
        try {
            config = new ModelConfigLoader().loadOrCreate(properties.getConfigFile());
        } catch (Exception e) {
            config = new ModelGatewayConfig();
            updateStatus("配置读取失败：" + e.getMessage(), false);
        }
        for (ProviderConfig provider : config.getProviders()) {
            providerCards.add(new ProviderCard(provider));
        }
        for (ModelConfig model : config.getModels()) {
            modelCards.add(new ModelCard(model));
        }
        defaultModelCombo.getItems().setAll(currentAliases());
        if (config.getDefaultModel() != null && defaultModelCombo.getItems().contains(config.getDefaultModel())) {
            defaultModelCombo.getSelectionModel().select(config.getDefaultModel());
        } else if (!defaultModelCombo.getItems().isEmpty()) {
            defaultModelCombo.getSelectionModel().selectFirst();
        }
        rebuildBoxes();
        // 工具开关（2.5）：运行时持有器优先，回退 tools.yml 持久化文件
        var context = AppContextHolder.get();
        settingsHolder = context != null ? context.getBean(ToolsSettingsHolder.class) : null;
        ToolsProperties toolsProperties = context != null ? context.getBean(ToolsProperties.class) : null;
        toolsFile = toolsProperties != null ? toolsProperties.getConfigFile()
                : properties.getConfigFile().toAbsolutePath().getParent().resolve("tools.yml");
        ToolsSettings toolSettings = settingsHolder != null
                ? settingsHolder.current()
                : new ToolsSettingsStore().load(toolsFile);
        shellToggle.setSelected(toolSettings.shellEnabled());
        pythonToggle.setSelected(toolSettings.pythonEnabled());
        confirmationToggle.setSelected(toolSettings.confirmationRequired());
        // 文件沙箱根目录（2026-09：支持多个授权目录；空列表 = 仅默认工作区）
        Path defaultWorkspace = toolsProperties != null ? toolsProperties.getWorkspaceRoot()
                : toolsFile.toAbsolutePath().getParent().resolve("workspace");
        defaultWorkspaceHint.setText("留空列表 = 仅默认工作区：" + defaultWorkspace.toAbsolutePath().normalize());
        List<String> loadedRoots = new ArrayList<>(toolSettings.workspaceRoots());
        if (loadedRoots.isEmpty()) {
            loadedRoots.add(""); // 空列表也保留一行空行供填写（用户修正 3）
        }
        workspaceRootRows.clear();
        for (String root : loadedRoots) {
            workspaceRootRows.add(new WorkspaceRootRow(root));
        }
        rebuildWorkspaceRoots();
        // 上下文管理（Phase 4 ContextManager）：运行时持有器优先，回退 context.yml
        contextHolder = context != null ? context.getBean(ContextSettingsHolder.class) : null;
        contextFile = properties.getConfigFile().toAbsolutePath().getParent().resolve("context.yml");
        ContextSettings contextSettings = contextHolder != null
                ? contextHolder.current()
                : new ContextSettingsStore().load(contextFile);
        contextToggle.setSelected(contextSettings.enabled());
        contextStrategyCombo.getItems().addAll("滑动窗口（删除旧内容）", "滑动窗口 + 摘要（压缩保留）");
        contextStrategyCombo.getSelectionModel().select(
                contextSettings.strategy() == TrimStrategy.SUMMARIZE
                        ? "滑动窗口 + 摘要（压缩保留）" : "滑动窗口（删除旧内容）");
        contextStrategyCombo.setTooltip(new Tooltip(
                "滑动窗口：超预算时从最旧对话整轮删除；\n"
                        + "滑动窗口 + 摘要：被删除部分先经模型压缩为一段摘要保留在上下文最前"));
        contextBudgetField.setText(String.valueOf(contextSettings.maxInputTokens()));
        contextBudgetField.setTooltip(new Tooltip("输入 token 预算（含历史与当前输入），建议 8000~64000"));
        contextKeepRecentField.setText(String.valueOf(contextSettings.keepRecentTurns()));
        contextKeepRecentField.setTooltip(new Tooltip("强制保留最近 N 轮对话不被裁剪"));
        contextSummaryModelCombo.getItems().add("（当前默认模型）");
        contextSummaryModelCombo.getItems().addAll(modelGateway.availableModels().stream()
                .map(model -> model.alias()).sorted().toList());
        String summaryAlias = contextSettings.summarizeModelAlias();
        contextSummaryModelCombo.getSelectionModel().select(
                summaryAlias != null && contextSummaryModelCombo.getItems().contains(summaryAlias)
                        ? summaryAlias : "（当前默认模型）");
        contextSummaryModelCombo.setTooltip(new Tooltip("摘要策略使用的模型（默认 = 当前对话模型）"));
        // MCP 服务器（Phase 4 双轨制）：运行时持有器优先，回退 mcp.yml
        mcpHolder = context != null ? context.getBean(McpSettingsHolder.class) : null;
        mcpFile = properties.getConfigFile().toAbsolutePath().getParent().resolve("mcp.yml");
        McpSettings mcpSettings = mcpHolder != null
                ? mcpHolder.current()
                : new McpSettingsStore().load(mcpFile);
        for (McpServerConfig server : mcpSettings.servers()) {
            mcpCards.add(new McpServerCard(server));
        }
        rebuildBoxes();
        // QQ 官方机器人：装配的 Store 优先，回退配置目录 qq-im.yml
        qqStore = context != null ? context.getBean(QqSettingsStore.class) : null;
        qqFile = properties.getConfigFile().toAbsolutePath().getParent().resolve("qq-im.yml");
        QqSettings qqSettings = qqStore != null
                ? qqStore.current()
                : new QqSettingsStore(properties.getConfigFile().toAbsolutePath().getParent()).current();
        qqEnabledToggle.setSelected(qqSettings.enabled());
        qqAppIdField.setText(qqSettings.appId());
        qqSecretField.setText(qqSettings.appSecret());
        qqEnvCombo.getItems().addAll("正式环境（production）", "沙箱环境（sandbox）");
        // 数据保留（A3）：运行时持有器优先，回退 retention.yml
        retentionHolder = context != null
                ? context.getBean(RetentionSettingsHolder.class) : null;
        retentionService = context != null
                ? context.getBean(DataRetentionService.class) : null;
        backupService = context != null
                ? context.getBean(BackupService.class) : null;
        retentionFile = configDir().resolve("retention.yml");
        RetentionSettings retentionSettings = retentionHolder != null
                ? retentionHolder.current()
                : new RetentionSettingsStore().load(retentionFile);
        retentionEnabledToggle.setSelected(retentionSettings.enabled());
        retentionChatDaysField.setText(String.valueOf(retentionSettings.chatDays()));
        retentionLogDaysField.setText(String.valueOf(retentionSettings.logDays()));
        retentionAuditDaysField.setText(String.valueOf(retentionSettings.auditDays()));
        qqEnvCombo.getSelectionModel().select(
                "sandbox".equals(qqSettings.environment()) ? "沙箱环境（sandbox）" : "正式环境（production）");
        qqWebhookToggle.setSelected(qqSettings.webhookEnabled());
        qqWebhookPortField.setText(String.valueOf(qqSettings.webhookPort()));
        // Phase 4：专业版授权（硬件指纹绑定）
        licenseService = context != null
                ? context.getBean(com.omniforge.core.persistence.service.LicenseService.class) : null;
        if (licenseService != null) {
            licenseKeyField.setPromptText("粘贴授权密钥（与本机指纹绑定）");
            updateLicenseStatus();
        }
        updateStatus("配置文件：" + properties.getConfigFile().toAbsolutePath());

        // ---------- 顶部：← 返回 | ⚙ 标题 | 保存全部 ----------
        Button saveAllButton = new Button("保存全部并热重载");
        saveAllButton.getStyleClass().add("primary");
        saveAllButton.setOnAction(event -> saveAll());

        Button closeButton = new Button("← 返回主界面");
        closeButton.setOnAction(event -> {
            if (dialogStage != null) {
                dialogStage.close();
            }
        });

        Label title = new Label("⚙ 配置中心");
        title.getStyleClass().add("empty-title");
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        HBox top = new HBox(8, closeButton, title, topSpacer, saveAllButton);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(4, 10, 4, 10));
        VBox header = new VBox(2, top);
        header.setPadding(new Insets(10, 10, 0, 10));

        // ---------- 模块面板（各建一次并缓存：切换不重建，未保存的编辑保留） ----------
        Map<String, VBox> panels = new java.util.LinkedHashMap<>();
        panels.put(MODULE_MODELS, buildModelsPanel());
        panels.put(MODULE_ROUTING, buildRoutingPanel());
        panels.put(MODULE_MESSAGING, buildMessagingPanel());
        panels.put(MODULE_CONTEXT, buildContextPanel());
        panels.put(MODULE_TOOLS, buildToolsPanel());
        panels.put(MODULE_MCP, buildMcpPanel());
        panels.put(MODULE_LICENSE, buildLicensePanel());
        panels.put(MODULE_DATA, buildDataPanel());

        // ---------- 左栏模块导航 ----------
        ListView<String> nav = new ListView<>();
        nav.getStyleClass().add("config-sidebar");
        nav.setPrefWidth(200);
        nav.getItems().setAll(MODULES);

        // ---------- 右内容区：共享 ScrollPane，切换 setContent ----------
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("config-scroll");
        Runnable showModule = () -> {
            String name = nav.getSelectionModel().getSelectedItem();
            VBox panel = name == null ? null : panels.get(name);
            if (panel != null) {
                scroll.setContent(panel);
                Platform.runLater(() -> scroll.setVvalue(0)); // 切换归零，避免停在半屏
            }
        };
        nav.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, cur) -> {
                    if (cur != null) {
                        showModule.run();
                    }
                });

        // ---------- 底部全局状态条 ----------
        status.getStyleClass().add("status");
        Region bottomSpacer = new Region();
        HBox.setHgrow(bottomSpacer, Priority.ALWAYS);
        HBox bottom = new HBox(8, bottomSpacer, status);
        bottom.setPadding(new Insets(8, 10, 10, 10));
        bottom.setAlignment(Pos.CENTER_LEFT);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setLeft(nav);
        root.setCenter(scroll);
        root.setBottom(bottom);

        dialogStage = new Stage();
        Stage stage = dialogStage;
        stage.setTitle("OmniForge"); // 标题去重：页内已有「⚙ 配置中心」
        stage.setScene(new Scene(root, 1000, 680));
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        com.omniforge.ui.theme.ThemeManager.attach(stage.getScene());
        if (pendingModule != null) {
            for (Object item : nav.getItems()) {
                if (pendingModule.equals(item)) {
                    nav.getSelectionModel().select((String) item);
                    break;
                }
            }
            pendingModule = null;
        } else {
            nav.getSelectionModel().selectFirst();
        }
        stage.show();
    }

    // ---------- 模块面板构建（Batch1：每模块独立面板 + 分模块保存） ----------

    private VBox panelShell(String title, String description, VBox content,
                            String moduleName, String effectText, boolean refreshMain,
                            IoSnapshot snapshot) {
        VBox box = new VBox(10, sectionTitle(title));
        if (description != null) {
            Label desc = new Label(description);
            desc.getStyleClass().add("status");
            desc.setWrapText(true);
            box.getChildren().add(desc);
        }
        box.getChildren().add(content);
        if (moduleName != null) {
            box.getChildren().addAll(moduleSaveRow(moduleName, effectText, refreshMain, snapshot));
        }
        box.setPadding(new Insets(10));
        return box;
    }

    /** 模块底部：保存本模块 + 模块状态 + 生效说明（effect 作为静态说明展示在下方） */
    private VBox moduleSaveRow(String moduleName, String effectText, boolean refreshMain,
                               IoSnapshot snapshot) {
        Label moduleStatus = new Label();
        moduleStatus.getStyleClass().add("status");
        Button save = new Button("保存");
        save.getStyleClass().add("primary");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox row = new HBox(8, save, grow, moduleStatus);
        row.setAlignment(Pos.CENTER_LEFT);
        save.setOnAction(event -> saveModule(moduleName, moduleStatus, effectText, refreshMain, snapshot));
        Label effect = new Label(effectText);
        effect.getStyleClass().add("status");
        effect.setWrapText(true);
        VBox footer = new VBox(4, row, effect);
        return footer;
    }

    /** 模型管理面板：提供商 + 模型 + 默认模型 + 预设 + 分模块保存 */
    private VBox buildModelsPanel() {
        Button addProviderButton = new Button("＋ 添加提供商");
        addProviderButton.setOnAction(event -> {
            providerCards.add(new ProviderCard(ProviderConfig.TYPE_OPENAI_COMPATIBLE,
                    "https://api.example.com", KEY_ENV, ""));
            refreshProviderOptions();
            rebuildBoxes();
        });
        Button addModelButton = new Button("＋ 添加模型");
        addModelButton.setOnAction(event -> {
            modelCards.add(new ModelCard(null));
            refreshModelOptions();
            rebuildBoxes();
        });
        MenuButton presetMenu = new MenuButton("一键预设 ▾");
        presetMenu.getItems().addAll(
                presetItem("DeepSeek", () -> addPreset("deepseek", "openai-compatible",
                        "https://api.deepseek.com", "DEEPSEEK_API_KEY",
                        "deepseek-chat", "deepseek-chat", 0.27, 1.1)),
                presetItem("OpenAI", () -> addPreset("openai", "openai-compatible",
                        "https://api.openai.com", "OPENAI_API_KEY",
                        "gpt-4o-mini", "gpt-4o-mini", 0.15, 0.6)),
                presetItem("Moonshot/Kimi", () -> addPreset("moonshot", "openai-compatible",
                        "https://api.moonshot.cn", "MOONSHOT_API_KEY",
                        "kimi", "moonshot-v1-8k", 12, 12)),
                presetItem("通义千问", () -> addPreset("dashscope", "dashscope",
                        null, "DASHSCOPE_API_KEY",
                        "qwen-plus", "qwen-plus", 0.8, 2.0)),
                presetItem("自定义端点（如 Ollama）", () -> {
                    providerCards.add(new ProviderCard(ProviderConfig.TYPE_OPENAI_COMPATIBLE, "", KEY_PLAIN, ""));
                    modelCards.add(new ModelCard(null));
                    refreshProviderOptions();
                    refreshModelOptions();
                    rebuildBoxes();
                    updateStatus("已添加自定义提供商：填写名称与地址（如 http://localhost:11434），"
                            + "本地 Ollama 密钥可留空");
                }));
        HBox addRow = new HBox(8, addProviderButton, addModelButton, presetMenu);
        addRow.setAlignment(Pos.CENTER_LEFT);

        Label defaultLabel = new Label("默认模型（未指定时使用）");
        defaultLabel.getStyleClass().add("status");
        HBox defaultRow = new HBox(8, defaultLabel, defaultModelCombo);
        defaultRow.setPadding(new Insets(0, 0, 6, 0));

        VBox content = new VBox(12,
                addRow,
                sectionTitle("提供商 Providers"),
                new Label("接入哪些厂商/服务：类型二选一；密钥来源三选一，按提示填写即可"),
                providerBox,
                sectionTitle("模型 Models"),
                defaultRow,
                new Label("每个模型一行：别名随意起，模型 ID 选建议值或手输"),
                modelBox);
        return panelShell(MODULE_MODELS, null, content, MODULE_MODELS, EFFECT_MODELS, true, this::snapshotModels);
    }

    /** 路由规则面板（Batch2：层级名 / 默认策略 / 身份规则 / 复杂度评分） */
    private VBox buildRoutingPanel() {
        RoutingConfig routing = new RoutingConfigStore().load(configDir().resolve("routing.yml"));

        routingEnabledToggle.setSelected(routing.isEnabled());
        routingThresholdField.setText(String.valueOf(routing.getThreshold()));
        routingWeightTokenField.setText(String.valueOf(routing.getWeights().getOrDefault("tokenLength", 0.4)));
        routingWeightKeywordField.setText(String.valueOf(routing.getWeights().getOrDefault("keyword", 0.4)));
        routingWeightEmbeddingField.setText(String.valueOf(routing.getWeights().getOrDefault("embedding", 0.2)));
        routingKeywordsArea.setText(String.join("\n", routing.getKeywords()));
        routingKeywordsArea.setPrefRowCount(3);
        routingExemplarsArea.setText(String.join("\n", routing.getExemplars()));
        routingExemplarsArea.setPrefRowCount(3);
        // 层级名
        List<String> tierNames = List.of("基础", "标准", "高级", "旗舰", "顶配");
        VBox tierNamesBox = new VBox(6);
        for (int i = 0; i < 5; i++) {
            TextField field = routingTierNameFields[i];
            field.setText(routing.getTierNames().getOrDefault(i + 1, tierNames.get(i)));
            field.setPromptText(tierNames.get(i));
            HBox row = new HBox(8, new Label("层级 " + (i + 1) + " 名称"), field);
            row.setAlignment(Pos.CENTER_LEFT);
            tierNamesBox.getChildren().add(row);
        }
        // 默认策略
        routingStrategyCombo.getItems().setAll(ROUTING_STRATEGY_LABELS);
        routingStrategyCombo.getSelectionModel().select(strategyLabel(routing.getDefaultStrategy().type()));
        routingParamField.setText(strategyParamText(routing.getDefaultStrategy()));
        routingParamField.setPrefWidth(220);
        routingParamField.setTooltip(new Tooltip("自动：min 层级（空=不限）\n强制：固定层级\n范围：min-max\n排除：1,2,3"));
        routingParamField.setPromptText("自动=3 / 强制=5 / 范围=2-4 / 排除=1,2");
        Label defaultStrategyHint = helpLabel(
                "默认策略：请求未匹配任何身份规则时使用（多数部署只有“所有人”这一档策略）。\n"
                + "策略参数（默认策略与每条身份规则使用同一套）：\n"
                + "· 自动：参数=最小层级（可空=不限层级）。允许该层及以上，按问题复杂度在区间最低/最高档二选一。\n"
                + "· 强制：参数=固定层级（如 5）。一律使用该层模型，忽略复杂度。\n"
                + "· 范围：参数=min-max（如 2-4）。只允许区间内的层级，按复杂度在区间两端择一。\n"
                + "· 排除：参数=禁止的层级（逗号分隔，如 1,2）。除这些外其余层级可用，按复杂度两端择一。\n"
                + "规则指向的层级没有任何已分级模型时：若所有模型都未分级→回退旧版配置（legacy 兜底）；否则→回退默认模型并在日志告警。");
        HBox defaultStrategyRow = new HBox(8, new Label("类型"), routingStrategyCombo,
                new Label("参数"), routingParamField);
        defaultStrategyRow.setAlignment(Pos.CENTER_LEFT);
        // 规则
        Button addRuleButton = new Button("＋ 添加规则");
        addRuleButton.setOnAction(event -> {
            routingRuleRows.add(new RoutingRuleRow(null));
            rebuildRoutingRules();
        });
        routingRuleRows.clear();
        for (RoutingRule rule : routing.getRules()) {
            routingRuleRows.add(new RoutingRuleRow(rule));
        }
        rebuildRoutingRules();
        Label rulesHint = helpLabel(
                "身份键：GUI = os:用户名（如 os:Administrator）；IM = im:平台:发送者（如 im:wechat:user1、im:dingtalk:admin_001）。\n"
                + "通配符：* 匹配任意串、? 匹配单个字符。可用 os:*、im:*、im:wechat:*、im:dingtalk:admin_*、* 等；未命中任何规则 → 走上面的“默认策略”。\n"
                + "优先级：数值越小越先匹配（先匹配先生效）；两条规则命中同一身份时取优先级小者。\n"
                + "参数写法与默认策略一致：自动=最小层级（空=不限）/ 强制=层级 / 范围=min-max / 排除=1,2,3。\n"
                + "示例：想让所有微信用户只能用 5 层→规则 身份=im:wechat:*、类型=强制、参数=5；想让本机用户走自动且 3 层起步→身份=os:*、类型=自动、参数=3。");

        // 复杂度评分（高级）
        GridPane weightsGrid = new GridPane();
        weightsGrid.setHgap(8);
        weightsGrid.setVgap(6);
        weightsGrid.add(field("阈值"), 0, 0);
        weightsGrid.add(routingThresholdField, 1, 0);
        weightsGrid.add(field("权重 token 长度"), 0, 1);
        weightsGrid.add(routingWeightTokenField, 1, 1);
        weightsGrid.add(field("权重 关键词"), 0, 2);
        weightsGrid.add(routingWeightKeywordField, 1, 2);
        weightsGrid.add(field("权重 语义相似"), 0, 3);
        weightsGrid.add(routingWeightEmbeddingField, 1, 3);
        ColumnConstraints grow2 = new ColumnConstraints();
        grow2.setHgrow(Priority.ALWAYS);
        weightsGrid.getColumnConstraints().addAll(new ColumnConstraints(140), grow2);
        routingThresholdField.setPrefWidth(90);
        Label scoringHint = helpLabel(
                "用于“自动/范围/排除”在允许层级内决定取低档还是高档（“强制”忽略此评分）。\n"
                + "阈值：复杂度分达到该值（0~1）→ 取高档模型，否则取低档。\n"
                + "权重：token 长度 / 关键词命中 / 语义相似 三项信号占比；信号权重 0、缺失或执行异常时自动跳过并对其余信号权重归一化。\n"
                + "关键词（每行一个）：命中越多 → 越倾向“复杂 → 高档”。\n"
                + "样本（每行一条）：与待路由问题做向量相似度 → 命中样本主题则视为复杂，优先高档（引擎就绪时才计入）。");

        Label tierNamesDesc = helpLabel(
                "层级 1（最基础）~5（最强/最贵）。请先在“模型管理”给每个模型选择层级；“未分级”的模型不参与本页任何层级规则，"
                + "仅作为默认模型或旧版配置（legacy）的回退候选。层名仅作展示，可自定义，如把 3 层叫“高级”");

        VBox content = new VBox(12,
                routingEnabledToggle,
                helpLabel("本页控制“未手动指定模型”的对话如何自动选型（需专业版 Pro）。适用入口：GUI 直连、Agent、IM 消息；"
                        + "辩论始终由你手动挑选模型、不受本页约束。保存后立即生效（无需重启）。"),
                sectionTitle("层级名称（1~5）"),
                tierNamesDesc,
                tierNamesBox,
                sectionTitle("默认策略"),
                defaultStrategyHint,
                defaultStrategyRow,
                sectionTitle("身份规则（按优先级）"),
                rulesHint,
                addRuleButton,
                routingRulesBox,
                sectionTitle("复杂度评分（高级）"),
                scoringHint,
                weightsGrid,
                helpLabel("关键词（每行一个）"),
                routingKeywordsArea,
                helpLabel("样本（每行一条，供语义相似信号）"),
                routingExemplarsArea);
        return panelShell(MODULE_ROUTING, null, content, MODULE_ROUTING, EFFECT_ROUTING, false, this::snapshotRouting);
    }

    /** 帮助/说明文字（等宽弱化风格，自动换行） */
    private static Label helpLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("status");
        label.setWrapText(true);
        return label;
    }

    private static final List<String> ROUTING_STRATEGY_LABELS = List.of("自动", "强制", "范围", "排除");

    /** 重建规则行 UI（删除到 0 自动补一行空白供填写） */
    private void rebuildRoutingRules() {
        if (routingRuleRows.isEmpty()) {
            routingRuleRows.add(new RoutingRuleRow(null));
        }
        routingRulesBox.getChildren().setAll(
                routingRuleRows.stream().map(RoutingRuleRow::root).toList());
    }

    /** 保存路由规则：routing.yml（热生效靠 router 的 mtime 重载） */
    private IoAction snapshotRouting() {
        RoutingConfig config = buildRoutingConfigFromUi();
        return () -> new RoutingConfigStore().save(configDir().resolve("routing.yml"), config);
    }

    private RoutingConfig buildRoutingConfigFromUi() {
        Map<String, Double> weights = new java.util.LinkedHashMap<>();
        weights.put("tokenLength", parsePositive(routingWeightTokenField.getText(), 0.4));
        weights.put("keyword", parsePositive(routingWeightKeywordField.getText(), 0.4));
        weights.put("embedding", parsePositive(routingWeightEmbeddingField.getText(), 0.2));
        List<String> keywords = splitLines(routingKeywordsArea.getText());
        List<String> exemplars = splitLines(routingExemplarsArea.getText());
        Map<Integer, String> tierNames = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            String text = routingTierNameFields[i].getText() == null ? ""
                    : routingTierNameFields[i].getText().strip();
            tierNames.put(i + 1, text.isEmpty() ? List.of("基础", "标准", "高级", "旗舰", "顶配").get(i) : text);
        }
        RoutingStrategy defaultStrategy = parseStrategyUi(routingStrategyCombo.getValue(), routingParamField.getText());
        List<RoutingRule> rules = new ArrayList<>();
        for (RoutingRuleRow row : routingRuleRows) {
            String identity = row.identityText();
            if (identity.isEmpty()) {
                continue;
            }
            rules.add(new RoutingRule(row.priorityInt(), "", identity,
                    parseStrategyUi(row.typeValue(), row.paramText())));
        }
        // 保留 legacy 为空的显式声明；GUI 不维护 legacy（迁移已由 Store 完成）
        return new RoutingConfig(routingEnabledToggle.isSelected(),
                Math.max(0, Math.min(1, parsePositive(routingThresholdField.getText(), 0.6))),
                weights, keywords, exemplars, tierNames, defaultStrategy, rules, null, null);
    }

    private static List<String> splitLines(String text) {
        if (text == null) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split("\\R"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static double parsePositive(String text, double fallback) {
        try {
            double v = Double.parseDouble(text == null ? "" : text.strip());
            return v >= 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String strategyLabel(RoutingStrategyType type) {
        return switch (type) {
            case AUTO -> "自动";
            case FIXED -> "强制";
            case RANGE -> "范围";
            case EXCLUDE -> "排除";
        };
    }

    private static RoutingStrategyType strategyType(String label) {
        if ("强制".equals(label)) {
            return RoutingStrategyType.FIXED;
        }
        if ("范围".equals(label)) {
            return RoutingStrategyType.RANGE;
        }
        if ("排除".equals(label)) {
            return RoutingStrategyType.EXCLUDE;
        }
        return RoutingStrategyType.AUTO;
    }

    private static String strategyParamText(RoutingStrategy s) {
        return switch (s.type()) {
            case AUTO -> s.minTier() == null ? "" : String.valueOf(s.minTier());
            case FIXED -> String.valueOf(s.fixedTier());
            case RANGE -> s.minTier() + "-" + s.maxTier();
            case EXCLUDE -> s.excludeTiers().stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
        };
    }

    /** UI 参数 → RoutingStrategy；非法抛 IAE（由 saveModule 转模块状态错误） */
    private static RoutingStrategy parseStrategyUi(String typeLabel, String paramText) {
        RoutingStrategyType type = strategyType(typeLabel);
        String param = paramText == null ? "" : paramText.strip();
        try {
            return switch (type) {
                case AUTO -> param.isEmpty() ? RoutingStrategy.auto(null) : RoutingStrategy.auto(Integer.parseInt(param));
                case FIXED -> RoutingStrategy.fixed(Integer.parseInt(param));
                case RANGE -> {
                    String[] parts = param.split("-");
                    yield RoutingStrategy.range(Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()));
                }
                case EXCLUDE -> RoutingStrategy.exclude(java.util.Arrays.stream(param.split(","))
                        .map(String::strip).filter(s -> !s.isEmpty()).map(Integer::parseInt).toList());
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("策略参数无效（" + typeLabel + "，参数：" + param + "）", e);
        }
    }

    /** 路由规则行（Batch2 权限规则编辑） */
    private final class RoutingRuleRow {

        private final TextField priority = new TextField();
        private final TextField identity = new TextField();
        private final ComboBox<String> type = new ComboBox<>();
        private final TextField param = new TextField();
        private final HBox root;

        RoutingRuleRow(RoutingRule rule) {
            priority.setText(String.valueOf(rule == null ? 100 : rule.priority()));
            priority.setPrefWidth(56);
            identity.setPromptText("身份模式，如 im:wechat:* 或 os:admin_*");
            HBox.setHgrow(identity, Priority.ALWAYS);
            type.getItems().setAll(ROUTING_STRATEGY_LABELS);
            type.getSelectionModel().select(rule == null ? "自动" : strategyLabel(rule.strategy().type()));
            param.setPrefWidth(150);
            param.setText(rule == null ? "" : strategyParamText(rule.strategy()));
            param.setPromptText("见工具提示");
            param.setTooltip(new Tooltip("自动：min 层级（空=不限）/ 强制：层级 / 范围：min-max / 排除：1,2"));
            Button remove = new Button("✕");
            remove.getStyleClass().add("danger");
            remove.setOnAction(event -> {
                routingRuleRows.remove(this);
                rebuildRoutingRules();
            });
            HBox row = new HBox(8, new Label("优先级"), priority, identity, type, param, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            root = row;
        }

        HBox root() {
            return root;
        }

        String identityText() {
            return identity.getText() == null ? "" : identity.getText().strip();
        }

        String typeValue() {
            String value = type.getValue();
            return value == null ? "自动" : value;
        }

        String paramText() {
            return param.getText() == null ? "" : param.getText().strip();
        }

        int priorityInt() {
            try {
                return Integer.parseInt(priority.getText() == null ? "" : priority.getText().strip());
            } catch (NumberFormatException e) {
                return 100;
            }
        }
    }

    /** 消息接入面板：QQ 官方机器人（NapCat 技术预览仅提示） */
    private VBox buildMessagingPanel() {
        qqAppIdField.setPromptText("在 QQ 开放平台（bot.q.qq.com）注册机器人应用获得");
        qqSecretField.setPromptText("AppSecret（仅本机保存于 qq-im.yml）");
        qqAppIdField.setPrefWidth(220);
        qqSecretField.setPrefWidth(220);
        qqWebhookPortField.setPrefWidth(70);
        qqWebhookPortField.setPromptText("8080");
        qqEnvCombo.setTooltip(new Tooltip("未上架的机器人可用沙箱环境联调（沙箱地址以平台控制台为准）"));
        qqWebhookToggle.setTooltip(new Tooltip("需公网 HTTPS 地址，开放平台回调端口仅允许 80/443/8080/8443；\n"
                + "默认关闭：优先使用 WebSocket（无需公网，自动断线重连）"));
        HBox qqRow1 = new HBox(16, qqEnabledToggle, qqEnvCombo);
        qqRow1.setAlignment(Pos.CENTER_LEFT);
        HBox qqRow2 = new HBox(8,
                new Label("AppID"), qqAppIdField,
                new Label("AppSecret"), qqSecretField);
        qqRow2.setAlignment(Pos.CENTER_LEFT);
        HBox qqRow3 = new HBox(16, qqWebhookToggle, new Label("回调端口"), qqWebhookPortField);
        qqRow3.setAlignment(Pos.CENTER_LEFT);
        Label napcatHint = new Label(NAPCAT_HINT);
        napcatHint.getStyleClass().add("status");
        napcatHint.setWrapText(true);

        VBox content = new VBox(12,
                sectionTitle("QQ 官方机器人（推荐）"),
                new Label("官方合规渠道：在 QQ 开放平台注册机器人应用，填入 AppID/AppSecret 即可收发单聊与群@消息"
                        + "（群聊需 @ 机器人）。"),
                qqRow1,
                qqRow2,
                qqRow3,
                sectionTitle("NapCat 通道（技术预览）"),
                napcatHint);
        return panelShell(MODULE_MESSAGING, null, content,
                MODULE_MESSAGING, EFFECT_MESSAGING, false, this::snapshotMessaging);
    }

    /** 上下文管理面板 */
    private VBox buildContextPanel() {
        HBox contextRow1 = new HBox(16, contextToggle, contextStrategyCombo);
        contextRow1.setAlignment(Pos.CENTER_LEFT);
        contextBudgetField.setPrefWidth(90);
        contextKeepRecentField.setPrefWidth(60);
        HBox contextRow2 = new HBox(8,
                new Label("输入预算"), contextBudgetField,
                new Label("保留最近轮数"), contextKeepRecentField,
                new Label("摘要模型"), contextSummaryModelCombo);
        contextRow2.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12,
                new Label("多轮对话记忆：超预算自动裁剪旧内容，长对话可持续且不超模型窗口；关闭后每次只发送当前一条消息"),
                contextRow1,
                contextRow2);
        return panelShell(MODULE_CONTEXT, null, content,
                MODULE_CONTEXT, EFFECT_CONTEXT, false, this::snapshotContext);
    }

    /** 工具配置面板：shell/python 开关 + 多文件沙箱根目录 */
    private VBox buildToolsPanel() {
        HBox toolsRow = new HBox(16, shellToggle, pythonToggle);
        toolsRow.setAlignment(Pos.CENTER_LEFT);
        toolsRow.setPadding(new Insets(4, 0, 4, 0));
        Button addRootButton = new Button("＋ 添加沙箱目录");
        addRootButton.setOnAction(event -> {
            workspaceRootRows.add(new WorkspaceRootRow(""));
            rebuildWorkspaceRoots();
        });
        Label sandboxDesc = new Label("每行一个授权目录（绝对路径，如 D:\\data、E:\\docs）。"
                + "绝对路径须落在所列目录内；相对路径以默认工作区为根（默认工作区需在列表内才可用；留空列表即默认）。");
        sandboxDesc.getStyleClass().add("status");
        sandboxDesc.setWrapText(true);
        defaultWorkspaceHint.getStyleClass().add("status");
        defaultWorkspaceHint.setWrapText(true);
        HBox addRootRow = new HBox(8, addRootButton, defaultWorkspaceHint);
        addRootRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(defaultWorkspaceHint, Priority.ALWAYS);

        // U11：危险操作人工确认开关（写文件/Shell 等需确认工具的 HITL 总闸）
        confirmationToggle.setTooltip(new javafx.scene.control.Tooltip(
                "开启（默认）：Agent 调用写文件/删除/Shell 等危险操作前弹窗确认；\n"
                        + "关闭：不经确认直接执行（风险自担）。仅影响桌面版，Headless/IM 无弹窗通道。"));
        Label confirmationDesc = new Label(
                "开启后危险操作执行前弹窗（60 秒未响应视为拒绝）；关闭则直接执行，仅建议可信环境关闭。");
        confirmationDesc.getStyleClass().add("status");
        confirmationDesc.setWrapText(true);
        HBox confirmationRow = new HBox(16, confirmationToggle);
        confirmationRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12,
                new Label("修改后保存立即生效（无需重启）"),
                toolsRow,
                sectionTitle("危险操作人工确认"),
                confirmationRow,
                confirmationDesc,
                sectionTitle("文件沙箱根目录（可多个）"),
                sandboxDesc,
                workspaceRootsBox,
                addRootRow);
        return panelShell(MODULE_TOOLS, null, content,
                MODULE_TOOLS, EFFECT_TOOLS, false, this::snapshotTools);
    }

    /** MCP 服务器面板 */
    private VBox buildMcpPanel() {
        Button addMcpButton = new Button("＋ 添加 MCP 服务器");
        addMcpButton.setOnAction(event -> {
            mcpCards.add(new McpServerCard(null));
            rebuildBoxes();
        });
        HBox addRow = new HBox(addMcpButton);
        addRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12,
                addRow,
                new Label("⚠ 仅挂载可信的 MCP 服务器：其工具由外部进程/服务执行，不受本地沙箱约束。"
                        + "工具以 mcp_服务器名_工具名 前缀接入 Agent"),
                mcpBox);
        return panelShell(MODULE_MCP, null, content, MODULE_MCP, EFFECT_MCP, false, this::snapshotMcp);
    }

    /** License 授权面板：激活即时生效（无 yml 保存，用激活按钮替代"保存本模块"） */
    private VBox buildLicensePanel() {
        licenseStatus.getStyleClass().add("status");
        Button activateButton = new Button("激活专业版");
        activateButton.getStyleClass().add("primary");
        activateButton.setOnAction(event -> activateLicense());
        HBox licenseRow = new HBox(8, licenseKeyField, activateButton);
        licenseRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(licenseKeyField, Priority.ALWAYS);
        // 本机指纹：完整显示 + 一键复制（密钥签发/激活需比对指纹）
        licenseFingerprint.setEditable(false);
        licenseFingerprint.getStyleClass().add("mono");
        HBox.setHgrow(licenseFingerprint, Priority.ALWAYS);
        Button copyFingerprintButton = new Button("复制本机指纹");
        copyFingerprintButton.setOnAction(event -> copyFingerprint());
        HBox fingerprintRow = new HBox(8, new Label("本机指纹"), licenseFingerprint, copyFingerprintButton);
        fingerprintRow.setAlignment(Pos.CENTER_LEFT);
        Label effect = new Label(EFFECT_LICENSE);
        effect.getStyleClass().add("status");
        effect.setWrapText(true);

        VBox box = new VBox(10,
                sectionTitle(MODULE_LICENSE),
                new Label("激活后解锁：单场辩论最多 10 个模型（社区版 3 个）、白标等 Pro 功能。"
                        + "授权码绑定本机指纹，支持永久买断与订阅（到期自动回社区版，数据无损；续订粘贴新码即可）。"),
                licenseRow,
                licenseStatus,
                fingerprintRow,
                effect,
                contactRow());
        box.setPadding(new Insets(10));
        return box;
    }

    /** 数据管理面板：数据保留策略 + 升级备份 + 清空所有数据（A 级批次 A3/A4 增量） */
    private VBox buildDataPanel() {
        // 保留策略：开关 + 三类天数
        retentionEnabledToggle.setOnAction(e -> { /* 无联动；保存时统一生效 */ });
        retentionChatDaysField.setPrefColumnCount(4);
        retentionLogDaysField.setPrefColumnCount(4);
        retentionAuditDaysField.setPrefColumnCount(4);
        retentionChatDaysField.setTextFormatter(new javafx.scene.control.TextFormatter<String>(change ->
                change.getControlNewText().matches("\\d{0,4}") ? change : null));
        retentionLogDaysField.setTextFormatter(new javafx.scene.control.TextFormatter<String>(change ->
                change.getControlNewText().matches("\\d{0,4}") ? change : null));
        retentionAuditDaysField.setTextFormatter(new javafx.scene.control.TextFormatter<String>(change ->
                change.getControlNewText().matches("\\d{0,4}") ? change : null));
        VBox retentionBox = new VBox(8,
                retentionEnabledToggle,
                daysRow("对话会话保留天数", retentionChatDaysField, "90 天（最后活动=最新消息时间，连带删除辩论档案）"),
                daysRow("IM / 工具调用日志保留天数", retentionLogDaysField, "90 天"),
                daysRow("审计日志保留天数", retentionAuditDaysField, "180 天"));
        Label retentionDesc = new Label("默认关闭；开启后启动即清理一次，之后每日后台扫描。"
                + "知识库/向量切片/授权状态/配置/密钥库一律不动。");
        retentionDesc.getStyleClass().add("status");
        retentionDesc.setWrapText(true);

        // 立即清理（Q3-A 手动触发）：确认后执行并展示本次删除数
        Button cleanupButton = new Button("🧹 立即清理旧数据");
        cleanupButton.setOnAction(event -> confirmImmediateCleanup());
        // 立即备份（Q4-B 手动兜底）
        Button backupButton = new Button("💾 立即备份");
        backupButton.setOnAction(event -> runImmediateBackup());
        HBox retentionActions = new HBox(8, cleanupButton, backupButton);
        retentionActions.setAlignment(Pos.CENTER_LEFT);

        // 清空所有数据（被遗忘权简化实现，保持原有）
        Button clearDataButton = new Button("🗑 清空所有数据");
        clearDataButton.getStyleClass().add("danger");
        clearDataButton.setOnAction(event -> confirmClearData());
        HBox clearRow = new HBox(clearDataButton);
        clearRow.setAlignment(Pos.CENTER_LEFT);
        Label clearHint = new Label("删除对话记录、辩论记录、知识库向量与导出文件（保留模型配置与密钥库）。"
                + "此操作不可撤销，清空后请重启应用生效。");
        clearHint.getStyleClass().add("status");
        clearHint.setWrapText(true);

        VBox content = new VBox(12,
                sectionTitle("数据保留策略"),
                retentionDesc,
                retentionBox,
                retentionActions,
                sectionTitle("全部数据"),
                clearHint,
                clearRow);
        return panelShell(MODULE_DATA, null, content,
                MODULE_DATA, EFFECT_DATA, false, this::snapshotData);
    }

    private HBox daysRow(String label, javafx.scene.control.TextField field, String hintText) {
        field.setTooltip(new javafx.scene.control.Tooltip(hintText));
        HBox row = new HBox(8, new Label(label), field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** 数据模块保存：retention.yml + Holder 热生效（A3） */
    private IoAction snapshotData() {
        RetentionSettings settings =
                new RetentionSettings(
                        retentionEnabledToggle.isSelected(),
                        parseIntDefault(retentionChatDaysField.getText(), 90),
                        parseIntDefault(retentionLogDaysField.getText(), 90),
                        parseIntDefault(retentionAuditDaysField.getText(), 180));
        return () -> {
            new RetentionSettingsStore().save(retentionFile, settings);
            if (retentionHolder != null) {
                retentionHolder.update(settings);
            }
        };
    }

    /** 立即清理：确认后经 DataRetentionService 执行（仅 GUI 模式装配了该 Bean） */
    private void confirmImmediateCleanup() {
        if (retentionService == null) {
            Toast.show(dialogStage, "数据保留服务未装配（Headless/无 GUI）", false);
            return;
        }
        if (!retentionHolder.current().isEnabled()) {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.CONFIRMATION,
                    "保留策略当前为关闭。\n立即清理将按当前天数设置删除旧数据，是否继续？",
                    javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
            alert.setTitle("立即清理旧数据");
            alert.initOwner(dialogStage);
            com.omniforge.ui.theme.ThemeManager.attachDialog(alert);
            if (!alert.showAndWait().map(button -> button == javafx.scene.control.ButtonType.OK)
                    .orElse(false)) {
                return;
            }
        }
        Thread.ofVirtual().name("omniforge-retention-manual", 0).start(() -> {
            String summary = retentionService.runOnce();
            Platform.runLater(() -> Toast.show(dialogStage,
                    "清理完成" + (summary == null || summary.isBlank() ? "" : "：" + summary), true));
        });
    }

    /** 立即备份：经 BackupService 无条件备份并 Toast 路径（Q4-B） */
    private void runImmediateBackup() {
        if (backupService == null) {
            Toast.show(dialogStage, "备份服务未装配（Headless/无 GUI）", false);
            return;
        }
        Thread.ofVirtual().name("omniforge-backup-manual", 0).start(() -> {
            try {
                int copied = backupService.backupNow();
                String path = backupService.backupDir().toAbsolutePath().toString();
                Platform.runLater(() -> Toast.show(dialogStage,
                        copied > 0 ? "已备份 " + copied + " 个文件 → " + path
                                : "无库可备份（首启/内存库）", true));
            } catch (RuntimeException e) {
                Platform.runLater(() -> Toast.show(dialogStage, "备份失败：" + e.getMessage(), false));
            }
        });
    }

    private static int parseIntDefault(String text, int fallback) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---------- 预设模板 ----------

    private MenuItem presetItem(String title, Runnable action) {
        MenuItem item = new MenuItem(title);
        item.setOnAction(event -> action.run());
        return item;
    }

    private void addPreset(String providerName, String type, String baseUrl, String keyEnv,
                           String alias, String modelId, double inPrice, double outPrice) {
        if (providerCards.stream().noneMatch(card -> providerName.equals(card.nameText()))) {
            providerCards.add(new ProviderCard(type, baseUrl, KEY_ENV, keyEnv));
            providerCards.get(providerCards.size() - 1).nameField().setText(providerName);
        }
        if (modelCards.stream().noneMatch(card -> alias.equals(card.aliasText()))) {
            ModelCard card = new ModelCard(null);
            card.aliasField().setText(alias);
            card.providerCombo().getSelectionModel().select(providerName);
            card.modelIdCombo().getEditor().setText(modelId);
            card.priceInField().setText(String.valueOf(inPrice));
            card.priceOutField().setText(String.valueOf(outPrice));
            modelCards.add(card);
        }
        refreshProviderOptions();
        refreshModelOptions();
        rebuildBoxes();
        updateStatus("已添加预设：" + alias + "（记得把密钥换成你自己的）");
    }

    // ---------- 保存（Batch1 模块化：快照在 FX 线程，IO 在虚拟线程） ----------

    /** 后台 IO 动作：闭包携带已在 FX 线程序列化的数据，虚拟线程内绝不读取 JavaFX 控件 */
    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }

    /** FX 线程快照（可抛受检/运行时异常，由调用方捕获转为模块状态提示） */
    @FunctionalInterface
    private interface IoSnapshot {
        IoAction get() throws Exception;
    }

    /** 配置目录（各 yml / keys 落点） */
    private Path configDir() {
        return properties.getConfigFile().toAbsolutePath().getParent();
    }

    private long enabledMcpCount() {
        return mcpCards.stream()
                .filter(card -> !card.nameText().isBlank() && card.toConfig().enabled())
                .count();
    }

    /** 模型模块：models.yml + ModelGateway 热重载（序列化/校验异常向上抛，由调用方处理） */
    private IoAction snapshotModels() throws Exception {
        ModelGatewayConfig out = new ModelGatewayConfig();
        String selectedDefault = defaultModelCombo.getSelectionModel().getSelectedItem();
        List<String> aliases = currentAliases();
        out.setDefaultModel(selectedDefault != null && aliases.contains(selectedDefault)
                ? selectedDefault
                : (aliases.isEmpty() ? null : aliases.get(0)));
        com.omniforge.common.security.SecretKeyStore keyStore =
                new com.omniforge.common.security.SecretKeyStore(configDir().resolve("keys"));
        out.setProviders(providerCards.stream().map(card -> card.toProvider(keyStore)).toList());
        out.setModels(modelCards.stream().map(ModelCard::toModel).toList());
        out.validate(); // 复用网关校验，错误信息为中文（FX 线程执行）
        String yaml = yamlMapper.writeValueAsString(out);
        return () -> {
            Files.writeString(properties.getConfigFile(), yaml);
            modelGateway.reload(); // models.yml 写后热重载
        };
    }

    /** 工具模块：tools.yml + ToolsSettingsHolder 热生效（含多沙箱根目录与确认开关） */
    private IoAction snapshotTools() {
        ToolsSettings toolSettings = new ToolsSettings(
                shellToggle.isSelected(), pythonToggle.isSelected(), collectWorkspaceRoots(),
                confirmationToggle.isSelected());
        return () -> {
            new ToolsSettingsStore().save(toolsFile, toolSettings);
            if (settingsHolder != null) {
                settingsHolder.update(toolSettings);
            }
        };
    }

    /** 上下文模块：context.yml + ContextSettingsHolder 热生效 */
    private IoAction snapshotContext() {
        ContextSettings contextSettings = new ContextSettings(
                contextToggle.isSelected(),
                Math.max(1024, parseInt(contextBudgetField.getText(),
                        ContextSettings.DEFAULT_MAX_INPUT_TOKENS)),
                Math.max(0, parseInt(contextKeepRecentField.getText(),
                        ContextSettings.DEFAULT_KEEP_RECENT_TURNS)),
                "滑动窗口 + 摘要（压缩保留）".equals(contextStrategyCombo.getValue())
                        ? TrimStrategy.SUMMARIZE : TrimStrategy.SLIDING_WINDOW,
                "（当前默认模型）".equals(contextSummaryModelCombo.getValue())
                        ? null : contextSummaryModelCombo.getValue());
        return () -> {
            new ContextSettingsStore().save(contextFile, contextSettings);
            if (contextHolder != null) {
                contextHolder.update(contextSettings);
            }
        };
    }

    /** MCP 模块：mcp.yml + McpSettingsHolder 热生效（客户端重建连接） */
    private IoAction snapshotMcp() {
        McpSettings mcpSettings = new McpSettings(mcpCards.stream()
                .filter(card -> !card.nameText().isBlank())
                .map(McpServerCard::toConfig)
                .toList());
        return () -> {
            new McpSettingsStore().save(mcpFile, mcpSettings);
            if (mcpHolder != null) {
                mcpHolder.update(mcpSettings);
            }
        };
    }

    /** 消息接入模块：qq-im.yml（连接参数热生效；通道启停需重启） */
    private IoAction snapshotMessaging() {
        QqSettings qqSettings = new QqSettings(
                qqEnabledToggle.isSelected(),
                qqAppIdField.getText().strip(),
                qqSecretField.getText(),
                "沙箱环境（sandbox）".equals(qqEnvCombo.getValue()) ? "sandbox" : "production",
                qqWebhookToggle.isSelected(),
                parseInt(qqWebhookPortField.getText(), 8080),
                "/webhook/qq",
                "127.0.0.1",
                "");
        return () -> {
            if (qqStore != null) {
                qqStore.save(qqSettings);
            } else {
                new QqSettingsStore(qqFile.toAbsolutePath().getParent()).save(qqSettings);
            }
        };
    }

    /** 保存全部：先校验后全写（all-or-nothing，语义同旧 save()） */
    private void saveAll() {
        List<IoAction> actions = new ArrayList<>();
        try {
            actions.add(snapshotModels());
            actions.add(snapshotTools());
            actions.add(snapshotContext());
            actions.add(snapshotMcp());
            actions.add(snapshotMessaging());
        } catch (Exception e) {
            updateStatus("校验未通过：" + e.getMessage(), false);
            return;
        }
        int providerCount = providerCards.size();
        int modelCount = modelCards.size();
        long mcpEnabled = enabledMcpCount();
        Thread.ofVirtual().start(() -> {
            try {
                for (IoAction action : actions) {
                    action.run();
                }
                String summary = "已保存并热重载（" + providerCount + " 提供商 / " + modelCount
                        + " 模型 / " + mcpEnabled + " 个 MCP 服务器）—— 模型/工具/上下文/MCP 已即时生效"
                        + "（MCP 重建连接）；QQ 参数即时生效、通道启停需重启。";
                Platform.runLater(() -> {
                    updateStatus("✓ " + summary, true);
                    Toast.show(dialogStage, summary, true);
                    onSaved.run();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    updateStatus("保存失败：" + e.getMessage(), false);
                    Toast.show(dialogStage, "保存失败：" + e.getMessage(), false);
                });
            }
        });
    }

    /**
     * 保存单个模块（Batch1 分模块保存）：校验/FX 快照异常 → 该模块状态红、不启线程；
     * 成功 → 模块状态绿 + Toast；refreshMain 仅模型模块为 true（改动可用模型需刷新主界面）。
     */
    private void saveModule(String moduleName, Label moduleStatus, String effectText,
                            boolean refreshMain, IoSnapshot snapshot) {
        IoAction action;
        try {
            action = snapshot.get();
        } catch (Exception e) {
            Platform.runLater(() -> {
                moduleStatus.setText("校验未通过：" + e.getMessage());
                moduleStatus.getStyleClass().removeAll("status-success", "status-error");
                moduleStatus.getStyleClass().add("status-error");
                updateStatus("校验未通过：" + e.getMessage(), false);
            });
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                action.run();
                Platform.runLater(() -> {
                    moduleStatus.setText("✓ 已保存「" + moduleName + "」，" + effectText);
                    moduleStatus.getStyleClass().removeAll("status-success", "status-error");
                    moduleStatus.getStyleClass().add("status-success");
                    Toast.show(dialogStage, "已保存「" + moduleName + "」", true);
                    if (refreshMain) {
                        onSaved.run();
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    moduleStatus.setText("保存失败：" + e.getMessage());
                    moduleStatus.getStyleClass().removeAll("status-success", "status-error");
                    moduleStatus.getStyleClass().add("status-error");
                    updateStatus("保存失败：" + e.getMessage(), false);
                    Toast.show(dialogStage, "保存失败：" + e.getMessage(), false);
                });
            }
        });
    }

    // ---------- 界面辅助 ----------

    private void rebuildBoxes() {
        providerBox.getChildren().setAll(providerCards.stream().map(ProviderCard::root).toList());
        modelBox.getChildren().setAll(modelCards.stream().map(ModelCard::root).toList());
        mcpBox.getChildren().setAll(mcpCards.stream().map(McpServerCard::root).toList());
    }

    private void rebuildWorkspaceRoots() {
        workspaceRootsBox.getChildren().setAll(
                workspaceRootRows.stream().map(WorkspaceRootRow::root).toList());
    }

    /** 收集各目录行文本（strip 后滤空），供保存构造 ToolsSettings */
    private List<String> collectWorkspaceRoots() {
        return workspaceRootRows.stream()
                .map(WorkspaceRootRow::text)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private List<String> currentAliases() {
        return modelCards.stream().map(ModelCard::aliasText).filter(a -> !a.isBlank()).toList();
    }

    private void refreshProviderOptions() {
        List<String> names = providerCards.stream().map(ProviderCard::nameText).filter(n -> !n.isBlank()).toList();
        modelCards.forEach(card -> {
            ComboBox<String> combo = card.providerCombo();
            String selected = combo.getSelectionModel().getSelectedItem();
            if (selected == null || selected.isBlank()) {
                // 可编辑下拉的已加载值在 editor 里，必须保留——selectFirst 会覆盖导致提供商错乱
                String editorText = combo.getEditor().getText();
                selected = editorText == null ? "" : editorText.trim();
            }
            combo.getItems().setAll(names);
            if (names.contains(selected)) {
                combo.getSelectionModel().select(selected);
            } else if (!selected.isBlank()) {
                combo.getEditor().setText(selected); // 保留原值（如引用的提供商已删除）
            } else if (!names.isEmpty()) {
                combo.getSelectionModel().selectFirst();
            }
        });
    }

    private void refreshModelOptions() {
        List<String> aliases = currentAliases();
        String selected = defaultModelCombo.getSelectionModel().getSelectedItem();
        defaultModelCombo.getItems().setAll(aliases);
        if (selected != null && aliases.contains(selected)) {
            defaultModelCombo.getSelectionModel().select(selected);
        } else if (!aliases.isEmpty()) {
            defaultModelCombo.getSelectionModel().selectFirst();
        }
    }

    /** 中性状态提示（信息类） */
    private void updateStatus(String text) {
        updateStatus(text, null);
    }

    /**
     * 状态提示（2026-09 颜色优化）：成功绿 / 失败红 / 中性灰，
     * 高对比度解决暗色下"保存并热重载"提示看不清问题。
     */
    private void updateStatus(String text, Boolean success) {
        status.setText(text);
        status.getStyleClass().removeAll("status-success", "status-error");
        if (Boolean.TRUE.equals(success)) {
            status.getStyleClass().add("status-success");
        } else if (Boolean.FALSE.equals(success)) {
            status.getStyleClass().add("status-error");
        }
    }

    private void activateLicense() {
        if (licenseService == null) {
            return;
        }
        boolean ok = licenseService.install(licenseKeyField.getText());
        if (ok) {
            licenseKeyField.clear();
            Toast.show(dialogStage, "专业版已激活", true);
        } else {
            String text = licenseKeyField.getText();
            String reason = text == null || !text.trim().startsWith("OF2")
                    ? "激活失败：授权码格式不识别（应为 OF2|指纹|到期日|签名，请核对复制是否完整）"
                    : "激活失败：指纹不符、签名无效或授权码已过期（详见日志；续订请重新索取）";
            Toast.show(dialogStage, reason, false);
        }
        updateLicenseStatus();
    }

    /** 复制完整本机指纹到剪贴板（密钥签发/激活需比对指纹） */
    private void copyFingerprint() {
        if (licenseService == null || dialogStage == null) {
            return;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(licenseService.fingerprint());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        Toast.show(dialogStage, "本机指纹已复制到剪贴板", true);
    }

    private void updateLicenseStatus() {
        if (licenseService == null) {
            return;
        }
        if (licenseService.isPro()) {
            String expiry = licenseService.proExpiry()
                    .map(date -> {
                        long days = java.time.temporal.ChronoUnit.DAYS.between(
                                java.time.LocalDate.now(), date);
                        return "有效期至 " + date + "（剩余 " + days + " 天）";
                    })
                    .orElse("永久授权");
            licenseStatus.setText("✓ 专业版已激活（" + expiry
                    + "；单场辩论上限 " + licenseService.maxDebateModels() + " 模型）");
        } else if (licenseService.proExpiry().isPresent()) {
            licenseStatus.setText("订阅已于 " + licenseService.proExpiry().get()
                    + " 到期，已回退社区版（数据无损；续订请索取新授权码重新激活）");
        } else {
            licenseStatus.setText("当前为社区版（单场辩论上限 3 模型）");
        }
        licenseFingerprint.setText(licenseService.fingerprint());
    }

    /** 商务联系行（获取授权/续订/企业版咨询） */
    private HBox contactRow() {
        Label label = new Label("获取授权 / 续订 / 企业版：corbin_guo@qq.com"
                + "（把「本机指纹」发到该邮箱即可申请授权码）");
        label.getStyleClass().add("status");
        label.setWrapText(true);
        Button copyButton = new Button("复制邮箱");
        copyButton.setOnAction(event -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString("corbin_guo@qq.com");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            Toast.show(dialogStage, "邮箱已复制到剪贴板", true);
        });
        HBox row = new HBox(8, label, copyButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** 清空所有数据（被遗忘权简化实现）：确认后删除数据文件，保留配置与密钥库 */
    private void confirmClearData() {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.setTitle("清空所有数据");
        alert.setHeaderText("将永久删除：对话记录、辩论记录、知识库向量、导出文件");
        alert.setContentText("此操作不可撤销。模型配置（models.yml）与密钥库将被保留。是否继续？");
        alert.initOwner(dialogStage);
        // 内建 Alert 的场景 show() 时才创建：挂主题避免暗色下弹出白色系统对话框
        com.omniforge.ui.theme.ThemeManager.attachDialog(alert);
        alert.showAndWait().ifPresent(button -> {
            if (button == javafx.scene.control.ButtonType.OK) {
                clearDataFiles();
            }
        });
    }

    private void clearDataFiles() {
        Path configDir = properties.getConfigFile().toAbsolutePath().getParent();
        Path[] targets = {
                configDir.resolve("omniforge.db"),
                configDir.resolve("omniforge.db-shm"),
                configDir.resolve("omniforge.db-wal"),
                configDir.resolve("vectors"),
                configDir.resolve("exports")};
        int removed = 0;
        for (Path target : targets) {
            try {
                if (Files.isDirectory(target)) {
                    try (var walk = Files.walk(target)) {
                        for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(path);
                        }
                    }
                    removed++;
                } else if (Files.deleteIfExists(target)) {
                    removed++;
                }
            } catch (Exception e) {
                Toast.show(dialogStage, "删除失败：" + target.getFileName() + "（" + e.getMessage() + "）", false);
                return;
            }
        }
        Toast.show(dialogStage, "已清空数据（" + removed + " 项），请重启应用生效", true);
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("header");
        return label;
    }

    // ---------- 提供商卡片 ----------

    private final class ProviderCard {

        private final TextField name = new TextField();
        private final ComboBox<String> type = new ComboBox<>();
        private final TextField baseUrl = new TextField();
        private final ComboBox<String> keySource = new ComboBox<>();
        private final TextField keyValue = new TextField();
        private final VBox root;
        /** true = keyValue 是已存在的 keystore 引用 ID（加载自配置文件），保存时不再重复加密 */
        private boolean keyValueIsId = false;

        ProviderCard(ProviderConfig config) {
            this(config.getType(), config.getBaseUrl(),
                    sourceOf(config.getApiKey()), valueOf(config.getApiKey()));
            name.setText(config.getName());
            String apiKey = config.getApiKey();
            if (apiKey != null && apiKey.startsWith("keystore:")) {
                String id = apiKey.substring("keystore:".length());
                Path keyFile = properties.getConfigFile().toAbsolutePath().getParent()
                        .resolve("keys").resolve(id + ".key");
                // 引用文件存在 → 合法 ID（保存时原样引用）；
                // 不存在 → 历史误存的真实密钥（如 keys 目录名异常），保存时自动加密修复
                keyValueIsId = Files.exists(keyFile);
            }
        }

        ProviderCard(String defaultType, String defaultBaseUrl, String defaultSource, String defaultValue) {
            type.getItems().addAll(ProviderConfig.TYPE_DASHSCOPE, ProviderConfig.TYPE_OPENAI_COMPATIBLE);
            type.getSelectionModel().select(defaultType == null ? ProviderConfig.TYPE_OPENAI_COMPATIBLE : defaultType);
            baseUrl.setText(defaultBaseUrl == null ? "" : defaultBaseUrl);
            baseUrl.setPromptText("https://api.deepseek.com（不要带 /v1，框架自动追加）");
            keySource.getItems().addAll(KEY_ENV, KEY_STORE, KEY_PLAIN);
            keySource.getSelectionModel().select(defaultSource);
            keySource.setTooltip(new Tooltip(
                    "环境变量：从系统环境变量读取（最安全，推荐）；\n"
                            + "加密存储：密钥经 JCE 加密落盘（~/.omniforge/keys/）；\n"
                            + "明文：直接写在配置里（仅个人测试）"));
            keyValue.setText(defaultValue == null ? "" : defaultValue);
            keyValue.setPromptText("变量名，如 DASHSCOPE_API_KEY");
            keyValue.setTooltip(new Tooltip("按所选来源填写：环境变量名 / 密钥（保存时自动加密） / 密钥明文"));
            keySource.valueProperty().addListener((obs, old, cur) -> keyValue.setPromptText(switch (cur) {
                case KEY_ENV -> "变量名，如 DASHSCOPE_API_KEY";
                case KEY_STORE -> "粘贴真实密钥，保存时自动加密存储";
                default -> "sk-... 密钥明文（不推荐）";
            }));
            type.valueProperty().addListener((obs, old, cur) -> {
                boolean openaiCompatible = ProviderConfig.TYPE_OPENAI_COMPATIBLE.equals(cur);
                baseUrl.setVisible(openaiCompatible);
                baseUrl.setManaged(openaiCompatible);
            });
            type.getSelectionModel().select(defaultType == null
                    ? ProviderConfig.TYPE_OPENAI_COMPATIBLE : defaultType);

            Button remove = new Button("删除");
            remove.getStyleClass().add("danger");
            remove.setOnAction(event -> {
                providerCards.remove(this);
                refreshProviderOptions();
                rebuildBoxes();
            });

            GridPane grid = new GridPane();
            grid.setHgap(8);
            grid.setVgap(6);
            grid.add(field("名称"), 0, 0);
            grid.add(name, 1, 0);
            grid.add(field("类型"), 0, 1);
            grid.add(type, 1, 1);
            grid.add(field("接口地址 base-url"), 0, 2);
            grid.add(baseUrl, 1, 2);
            grid.add(field("密钥来源"), 0, 3);
            grid.add(keySource, 1, 3);
            grid.add(field("密钥"), 0, 4);
            grid.add(keyValue, 1, 4);
            ColumnConstraints grow = new ColumnConstraints();
            grow.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().addAll(new ColumnConstraints(110), grow);

            HBox header = new HBox(8, name, remove);
            header.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(name, Priority.ALWAYS);
            VBox card = new VBox(6, header, grid);
            card.getStyleClass().add("config-card");
            card.setPadding(new Insets(10));
            root = card;
        }

        VBox root() {
            return root;
        }

        String nameText() {
            return name.getText() == null ? "" : name.getText().trim();
        }

        TextField nameField() {
            return name;
        }

        ProviderConfig toProvider(com.omniforge.common.security.SecretKeyStore keyStore) {
            ProviderConfig config = new ProviderConfig();
            config.setName(nameText());
            config.setType(type.getValue());
            if (ProviderConfig.TYPE_OPENAI_COMPATIBLE.equals(type.getValue())) {
                config.setBaseUrl(baseUrl.getText() == null || baseUrl.getText().isBlank()
                        ? null : baseUrl.getText().trim());
            }
            config.setApiKey(toApiKeyReference(keyStore));
            return config;
        }

        private String toApiKeyReference(com.omniforge.common.security.SecretKeyStore keyStore) {
            String value = keyValue.getText() == null ? "" : keyValue.getText().trim();
            if (value.isEmpty()) {
                return null;
            }
            return switch (keySource.getValue()) {
                case KEY_ENV -> value.startsWith("${") ? value : "${" + value + "}";
                case KEY_STORE -> {
                    if (keyValueIsId || value.startsWith("keystore:")) {
                        yield value.startsWith("keystore:") ? value : "keystore:" + value;
                    }
                    // 用户粘贴的是真实密钥：加密落盘后以 ID 引用
                    String id = nameText().isBlank() ? "provider" : nameText().replaceAll("[^a-zA-Z0-9_-]", "_");
                    keyStore.writeSecret(
                            properties.getConfigFile().toAbsolutePath().getParent().resolve("keys").resolve(id + ".key"),
                            value);
                    yield "keystore:" + id;
                }
                default -> value;
            };
        }

        private static String sourceOf(String apiKey) {
            if (apiKey == null) {
                return KEY_ENV;
            }
            if (apiKey.startsWith("${") && apiKey.endsWith("}")) {
                return KEY_ENV;
            }
            if (apiKey.startsWith("keystore:")) {
                return KEY_STORE;
            }
            return KEY_PLAIN;
        }

        private static String valueOf(String apiKey) {
            if (apiKey == null) {
                return "";
            }
            if (apiKey.startsWith("${") && apiKey.endsWith("}")) {
                return apiKey.substring(2, apiKey.length() - 1);
            }
            if (apiKey.startsWith("keystore:")) {
                return apiKey.substring("keystore:".length());
            }
            return apiKey;
        }
    }

    // ---------- 模型卡片 ----------

    private final class ModelCard {

        private final TextField alias = new TextField();
        private final ComboBox<String> provider = new ComboBox<>();
        private final ComboBox<String> modelId = new ComboBox<>();
        private final TextField priceIn = new TextField();
        private final TextField priceOut = new TextField();
        private final TextField timeout = new TextField();
        private final TextField contextWindow = new TextField();
        private final ComboBox<String> tier = new ComboBox<>();
        private final VBox root;

        ModelCard(ModelConfig config) {
            provider.setEditable(true);
            modelId.setEditable(true);
            modelId.getItems().addAll(MODEL_SUGGESTIONS.values().stream().flatMap(List::stream).distinct().toList());
            provider.valueProperty().addListener((obs, old, cur) -> refreshSuggestions());
            priceIn.setPromptText("如 0.27");
            priceOut.setPromptText("如 1.1");
            timeout.setPromptText("秒，默认 120");
            contextWindow.setPromptText("留空 = 未知（按全局预算）");
            contextWindow.setTooltip(new Tooltip(
                    "模型上下文窗口大小（token），如 65536/128000；\n"
                            + "留空时上下文裁剪仅按全局输入预算执行"));
            tier.getItems().addAll("未分级", "1", "2", "3", "4", "5");
            tier.setTooltip(new Tooltip("路由层级 1~5（Batch2 分层级路由；未分级=不参与分层规则，走默认/legacy）"));
            tier.getSelectionModel().select("未分级");
            if (config != null) {
                alias.setText(config.getAlias());
                provider.getEditor().setText(config.getProvider());
                modelId.getEditor().setText(config.getModelId());
                priceIn.setText(String.valueOf(config.getInputPricePer1m()));
                priceOut.setText(String.valueOf(config.getOutputPricePer1m()));
                timeout.setText(String.valueOf(config.getTimeoutSeconds()));
                contextWindow.setText(config.getContextWindowTokens() == null
                        ? "" : String.valueOf(config.getContextWindowTokens()));
                Integer tierValue = config.getTier();
                tier.getSelectionModel().select(tierValue == null ? "未分级" : String.valueOf(tierValue));
            }

            Button remove = new Button("删除");
            remove.getStyleClass().add("danger");
            remove.setOnAction(event -> {
                modelCards.remove(this);
                refreshModelOptions();
                rebuildBoxes();
            });

            GridPane grid = new GridPane();
            grid.setHgap(8);
            grid.setVgap(6);
            grid.add(field("别名（随意起）"), 0, 0);
            grid.add(alias, 1, 0);
            grid.add(field("所属提供商"), 0, 1);
            grid.add(provider, 1, 1);
            grid.add(field("模型 ID"), 0, 2);
            grid.add(modelId, 1, 2);
            grid.add(field("输入价（$/百万token）"), 0, 3);
            grid.add(priceIn, 1, 3);
            grid.add(field("输出价（$/百万token）"), 0, 4);
            grid.add(priceOut, 1, 4);
            grid.add(field("超时（秒）"), 0, 5);
            grid.add(timeout, 1, 5);
            grid.add(field("上下文窗口（token）"), 0, 6);
            grid.add(contextWindow, 1, 6);
            grid.add(field("层级（路由）"), 0, 7);
            grid.add(tier, 1, 7);
            ColumnConstraints grow = new ColumnConstraints();
            grow.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().addAll(new ColumnConstraints(140), grow);

            HBox header = new HBox(8, alias, remove);
            header.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(alias, Priority.ALWAYS);
            VBox card = new VBox(6, header, grid);
            card.getStyleClass().add("config-card");
            card.setPadding(new Insets(10));
            root = card;
        }

        VBox root() {
            return root;
        }

        String aliasText() {
            return alias.getText() == null ? "" : alias.getText().trim();
        }

        TextField aliasField() {
            return alias;
        }

        ComboBox<String> providerCombo() {
            return provider;
        }

        ComboBox<String> modelIdCombo() {
            return modelId;
        }

        TextField priceInField() {
            return priceIn;
        }

        TextField priceOutField() {
            return priceOut;
        }

        ModelConfig toModel() {
            ModelConfig config = new ModelConfig();
            config.setAlias(aliasText());
            config.setProvider(provider.getEditor().getText() == null ? "" : provider.getEditor().getText().trim());
            config.setModelId(modelId.getEditor().getText() == null ? "" : modelId.getEditor().getText().trim());
            config.setInputPricePer1m(parseDouble(priceIn.getText(), 0));
            config.setOutputPricePer1m(parseDouble(priceOut.getText(), 0));
            config.setTimeoutSeconds((int) parseDouble(timeout.getText(), 120));
            String windowText = contextWindow.getText() == null ? "" : contextWindow.getText().trim();
            config.setContextWindowTokens(windowText.isBlank()
                    ? null : (int) parseDouble(windowText, 0));
            String tierValue = tier.getValue();
            config.setTier(tierValue == null || "未分级".equals(tierValue) ? null : Integer.parseInt(tierValue));
            return config;
        }

        private void refreshSuggestions() {
            String providerName = provider.getEditor().getText() == null ? "" : provider.getEditor().getText().trim();
            ProviderCard card = providerCards.stream()
                    .filter(p -> providerName.equals(p.nameText()))
                    .findFirst()
                    .orElse(null);
            String type = card == null ? null : card.type.getValue();
            List<String> suggestions = type == null ? List.of() : MODEL_SUGGESTIONS.getOrDefault(type, List.of());
            modelId.getItems().setAll(suggestions.isEmpty()
                    ? MODEL_SUGGESTIONS.values().stream().flatMap(List::stream).distinct().toList()
                    : suggestions);
        }

        private static double parseDouble(String text, double fallback) {
            try {
                return Double.parseDouble(text == null ? "" : text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    private static Label field(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("status");
        return label;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---------- 沙箱根目录行（2026-09：多授权目录） ----------

    private final class WorkspaceRootRow {

        private final TextField value = new TextField();
        private final HBox root;

        WorkspaceRootRow(String text) {
            value.setText(text == null ? "" : text);
            value.setPromptText("目录路径（如 D:\\data 或 E:\\docs）");
            value.setPrefWidth(420);
            HBox.setHgrow(value, Priority.ALWAYS);
            Button remove = new Button("✕");
            remove.getStyleClass().add("danger");
            remove.setTooltip(new Tooltip("移除该目录"));
            remove.setOnAction(event -> {
                workspaceRootRows.remove(this);
                if (workspaceRootRows.isEmpty()) {
                    workspaceRootRows.add(new WorkspaceRootRow("")); // 删除到 0 自动补一行空行
                }
                rebuildWorkspaceRoots();
            });
            HBox row = new HBox(8, value, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            root = row;
        }

        HBox root() {
            return root;
        }

        String text() {
            return value.getText() == null ? "" : value.getText().trim();
        }
    }

    // ---------- MCP 服务器卡片 ----------

    private final class McpServerCard {

        private static final String TRANSPORT_STDIO_LABEL = "stdio · 本地进程";
        private static final String TRANSPORT_HTTP_LABEL = "http · 远程服务（streamable HTTP）";

        private final TextField name = new TextField();
        private final ComboBox<String> transport = new ComboBox<>();
        private final TextField command = new TextField();
        private final TextField args = new TextField();
        private final TextField url = new TextField();
        private final CheckBox enabled = new CheckBox("启用");
        private final VBox root;

        McpServerCard(McpServerConfig config) {
            transport.getItems().addAll(TRANSPORT_STDIO_LABEL, TRANSPORT_HTTP_LABEL);
            name.setPromptText("如 filesystem");
            command.setPromptText("如 npx");
            args.setPromptText("启动参数（空格分隔），如 -y @modelcontextprotocol/server-filesystem C:/data");
            url.setPromptText("如 http://localhost:9000/mcp");
            url.setTooltip(new Tooltip("MCP streamable HTTP 服务端点"));
            if (config != null) {
                name.setText(config.name());
                transport.getSelectionModel().select(McpServerConfig.TRANSPORT_STDIO.equals(config.transport())
                        ? TRANSPORT_STDIO_LABEL : TRANSPORT_HTTP_LABEL);
                command.setText(config.command() == null ? "" : config.command());
                args.setText(String.join(" ", config.args()));
                url.setText(config.url() == null ? "" : config.url());
                enabled.setSelected(config.enabled());
            } else {
                transport.getSelectionModel().select(TRANSPORT_STDIO_LABEL);
            }
            transport.valueProperty().addListener((obs, old, cur) -> toggleTransportFields());

            Button remove = new Button("删除");
            remove.getStyleClass().add("danger");
            remove.setOnAction(event -> {
                mcpCards.remove(this);
                rebuildBoxes();
            });

            GridPane grid = new GridPane();
            grid.setHgap(8);
            grid.setVgap(6);
            grid.add(field("名称"), 0, 0);
            grid.add(name, 1, 0);
            grid.add(field("传输方式"), 0, 1);
            grid.add(transport, 1, 1);
            grid.add(field("命令"), 0, 2);
            grid.add(command, 1, 2);
            grid.add(field("参数"), 0, 3);
            grid.add(args, 1, 3);
            grid.add(field("URL"), 0, 4);
            grid.add(url, 1, 4);
            ColumnConstraints grow = new ColumnConstraints();
            grow.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().addAll(new ColumnConstraints(110), grow);

            HBox header = new HBox(8, name, enabled, remove);
            header.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(name, Priority.ALWAYS);
            VBox card = new VBox(6, header, grid);
            card.getStyleClass().add("config-card");
            card.setPadding(new Insets(10));
            root = card;
            toggleTransportFields();
        }

        VBox root() {
            return root;
        }

        String nameText() {
            return name.getText() == null ? "" : name.getText().trim();
        }

        McpServerConfig toConfig() {
            boolean stdio = TRANSPORT_STDIO_LABEL.equals(transport.getValue());
            List<String> argList = args.getText() == null || args.getText().isBlank()
                    ? List.of() : List.of(args.getText().trim().split("\\s+"));
            return new McpServerConfig(nameText(),
                    stdio ? McpServerConfig.TRANSPORT_STDIO : McpServerConfig.TRANSPORT_HTTP,
                    command.getText() == null ? "" : command.getText().trim(),
                    argList,
                    url.getText() == null ? "" : url.getText().trim(),
                    enabled.isSelected());
        }

        private void toggleTransportFields() {
            boolean stdio = TRANSPORT_STDIO_LABEL.equals(transport.getValue());
            command.setVisible(stdio);
            command.setManaged(stdio);
            args.setVisible(stdio);
            args.setManaged(stdio);
            url.setVisible(!stdio);
            url.setManaged(!stdio);
            // 网格行随可见性自动收起
        }
    }
}
