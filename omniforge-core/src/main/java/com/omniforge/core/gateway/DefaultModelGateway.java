package com.omniforge.core.gateway;

import com.omniforge.common.exception.ConfigurationException;
import com.omniforge.common.exception.ModelGatewayException;
import com.omniforge.common.exception.ModelRateLimitException;
import com.omniforge.common.exception.ModelTimeoutException;
import com.omniforge.core.scheduler.OmniForgeExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型网关默认实现（需求 4.1）。
 *
 * <p>关键机制：
 * <ul>
 *   <li><b>热重载</b>：{@link AtomicReference} 持有不可变快照，reload() 整体替换；</li>
 *   <li><b>超时熔断</b>：每模型独立超时（models.yml 中 timeout-seconds），虚拟线程 + orTimeout 实现；</li>
 *   <li><b>故障隔离</b>：单模型调用异常被包装抛出，不影响其他模型与其他调用（需求 9.3）；</li>
 *   <li><b>成本计量</b>：每次调用向 {@link UsageTracker} 记账（成本熔断的数据基础）。</li>
 * </ul>
 *
 * <p>单模型初始化失败（如 API Key 缺失）仅跳过该模型并记录告警，
 * 其余模型不受影响；全部失败时网关保持空状态，等待配置修复后热重载。</p>
 */
public class DefaultModelGateway implements ModelGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultModelGateway.class);

    private final Path configFile;
    private final ModelConfigLoader configLoader;
    private final ChatModelProvider chatModelProvider;
    private final ApiKeyResolver apiKeyResolver;
    private final ModelRouter router;
    private final UsageTracker usageTracker = new UsageTracker();
    private final ExecutorService executor = OmniForgeExecutors.newVirtualThreadPerTaskExecutor("omniforge-model");

    /** 当前生效状态（原子热交换，实现 models.yml 热重载） */
    private final AtomicReference<GatewayState> state = new AtomicReference<>();

    public DefaultModelGateway(Path configFile, ModelConfigLoader configLoader,
                               ChatModelProvider chatModelProvider, ApiKeyResolver apiKeyResolver,
                               ModelRouter router) {
        this.configFile = Objects.requireNonNull(configFile, "configFile");
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
        this.chatModelProvider = Objects.requireNonNull(chatModelProvider, "chatModelProvider");
        this.apiKeyResolver = Objects.requireNonNull(apiKeyResolver, "apiKeyResolver");
        this.router = Objects.requireNonNull(router, "router");
        reload();
    }

    @Override
    public GatewayChatResult chat(GatewayRequest request) {
        Objects.requireNonNull(request, "request");
        GatewayState snapshot = state.get();
        if (snapshot.config() == null || snapshot.runtimes().isEmpty()) {
            throw new ModelGatewayException("网关尚未配置可用模型：请检查 " + configFile + " 与 API Key 配置");
        }
        String alias = router.route(request, snapshot.config());
        ModelRuntime runtime = snapshot.runtime(alias);
        long startNanos = System.nanoTime();
        try {
            ChatResponse response = CompletableFuture
                    .supplyAsync(() -> callModel(runtime, request), executor)
                    .orTimeout(runtime.timeoutSeconds(), TimeUnit.SECONDS)
                    .join();
            return toResult(alias, runtime, response, elapsedMs(startNanos));
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TimeoutException) {
                throw new ModelTimeoutException(
                        "模型调用超时: alias=" + alias + "（>" + runtime.timeoutSeconds() + "s）", cause);
            }
            if (cause instanceof ModelRateLimitException rateLimitException) {
                throw rateLimitException;
            }
            if (cause instanceof ModelTimeoutException timeoutException) {
                throw timeoutException;
            }
            // 故障隔离：异常包装并携带模型上下文，不污染共享状态，不影响其他模型
            throw new ModelGatewayException(
                    "模型调用失败: alias=" + alias + ", provider=" + runtime.providerName(), cause);
        }
    }

    @Override
    public Flux<String> streamText(GatewayRequest request) {
        Objects.requireNonNull(request, "request");
        GatewayState snapshot = state.get();
        if (snapshot.config() == null || snapshot.runtimes().isEmpty()) {
            return Flux.error(new ModelGatewayException(
                    "网关尚未配置可用模型：请检查 " + configFile + " 与 API Key 配置"));
        }
        String alias = router.route(request, snapshot.config());
        ModelRuntime runtime = snapshot.runtime(alias);
        List<Message> messages = buildMessages(request);
        ChatOptions options = chatModelProvider.buildOptions(runtime.provider(), request);
        Prompt prompt = options != null ? new Prompt(messages, options) : new Prompt(messages);
        try {
            return runtime.chatModel().stream(prompt)
                    .map(response -> response.getResult() != null && response.getResult().getOutput() != null
                            ? response.getResult().getOutput().getText() : "")
                    .filter(text -> text != null && !text.isEmpty())
                    .onErrorMap(e -> new ModelGatewayException(
                            "模型流式调用失败: alias=" + alias + ", provider=" + runtime.providerName(), e));
        } catch (RuntimeException e) {
            return Flux.error(new ModelGatewayException(
                    "模型流式调用失败: alias=" + alias + ", provider=" + runtime.providerName(), e));
        }
    }

    @Override
    public Collection<ModelInfo> availableModels() {
        return state.get().runtimes().values().stream()
                .map(r -> new ModelInfo(r.alias(), r.providerName(), r.modelId(),
                        r.inputPricePer1m(), r.outputPricePer1m(), r.timeoutSeconds(),
                        r.contextWindowTokens()))
                .toList();
    }

    @Override
    public GatewayUsage usage() {
        return usageTracker.snapshot();
    }

    @Override
    public ChatModel chatModel(String alias) {
        GatewayState snapshot = state.get();
        if (snapshot.config() == null || snapshot.runtimes().isEmpty()) {
            throw new ModelGatewayException("网关尚未配置可用模型：请检查 " + configFile + " 与 API Key 配置");
        }
        // SDK 直连路径（Agent/辩论/裁判/摘要共用）也需计入用量与成本：
        // 包装记账装饰器，否则状态栏的累计调用/估算成本恒为 0（P0 修复）
        return new UsageTrackingChatModel(snapshot.runtime(resolveAlias(alias)));
    }

    @Override
    public String resolveAlias(String alias) {
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        GatewayState snapshot = state.get();
        String defaultAlias = snapshot.config() != null ? snapshot.config().getDefaultModel() : null;
        if (defaultAlias == null || defaultAlias.isBlank()) {
            throw new ConfigurationException("未指定模型别名，且 models.yml 未配置 default-model");
        }
        return defaultAlias;
    }

    @Override
    public String routeAlias(String alias, String identityKey, String userText) {
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        GatewayState snapshot = state.get();
        ModelGatewayConfig config = snapshot.config();
        if (config == null) {
            return resolveAlias(null);
        }
        String text = (userText == null || userText.isBlank()) ? "x" : userText;
        GatewayRequest routed = new GatewayRequest(null, null, text, null, null,
                java.util.List.of(), identityKey);
        return router.route(routed, config);
    }

    @Override
    public void reload() {
        try {
            ModelGatewayConfig config = configLoader.loadOrCreate(configFile);
            Map<String, ModelRuntime> runtimes = buildRuntimes(config);
            state.set(new GatewayState(config, Map.copyOf(runtimes)));
            if (runtimes.isEmpty()) {
                log.error("模型配置已加载但 0 个模型可用：请检查 models.yml 与 API Key 配置");
            } else {
                log.info("模型配置已生效：{} 个模型可用 [{}]", runtimes.size(), String.join(", ", runtimes.keySet()));
            }
        } catch (ConfigurationException e) {
            // 配置解析失败：保持旧快照继续服务（热重载韧性），首次启动则保持空状态
            log.error("模型配置加载失败（沿用旧配置或保持空状态）：{}", e.getMessage());
            if (state.get() == null) {
                state.set(new GatewayState(null, Map.of()));
            }
        }
    }

    /** 关闭执行器：等待已提交的调用任务完成后再退出 */
    @Override
    public void close() {
        executor.close();
    }

    private Map<String, ModelRuntime> buildRuntimes(ModelGatewayConfig config) {
        Map<String, ModelRuntime> runtimes = new HashMap<>();
        for (ModelConfig modelConfig : config.getModels()) {
            try {
                ProviderConfig provider = config.findProvider(modelConfig.getProvider());
                String apiKey = apiKeyResolver.resolve(provider.getApiKey());
                ChatModel chatModel = chatModelProvider.create(provider, apiKey, modelConfig.getModelId());
                runtimes.put(modelConfig.getAlias(), new ModelRuntime(
                        modelConfig.getAlias(), provider.getName(), modelConfig.getModelId(), provider,
                        chatModel, modelConfig.getTimeoutSeconds(),
                        modelConfig.getInputPricePer1m(), modelConfig.getOutputPricePer1m(),
                        modelConfig.getContextWindowTokens()));
            } catch (RuntimeException e) {
                log.error("模型初始化失败，已跳过: alias={}，原因: {}", modelConfig.getAlias(), e.getMessage());
            }
        }
        return runtimes;
    }

    private ChatResponse callModel(ModelRuntime runtime, GatewayRequest request) {
        List<Message> messages = buildMessages(request);
        ChatOptions options = chatModelProvider.buildOptions(runtime.provider(), request);
        Prompt prompt = options != null ? new Prompt(messages, options) : new Prompt(messages);
        try {
            return runtime.chatModel().call(prompt);
        } catch (ModelRateLimitException e) {
            throw e;
        } catch (RuntimeException e) {
            if (isRateLimit(e)) {
                throw new ModelRateLimitException("模型触发限流: alias=" + runtime.alias(), e);
            }
            throw e;
        }
    }

    /** 请求消息组装：system? → 多轮历史 → 当前输入（历史由 ContextManager 裁剪后注入） */
    private static List<Message> buildMessages(GatewayRequest request) {
        List<Message> messages = new ArrayList<>(2 + request.history().size());
        if (request.systemText() != null && !request.systemText().isBlank()) {
            messages.add(new SystemMessage(request.systemText()));
        }
        for (GatewayHistoryMessage historyMessage : request.history()) {
            messages.add(toMessage(historyMessage));
        }
        messages.add(new UserMessage(request.userText()));
        return messages;
    }

    /** 网关中性历史消息 → Spring AI Message（system = 上下文摘要条目） */
    static Message toMessage(GatewayHistoryMessage historyMessage) {
        return switch (historyMessage.role()) {
            case SYSTEM -> new SystemMessage(historyMessage.text());
            case ASSISTANT -> new org.springframework.ai.chat.messages.AssistantMessage(historyMessage.text());
            case USER -> new UserMessage(historyMessage.text());
        };
    }

    private GatewayChatResult toResult(String alias, ModelRuntime runtime, ChatResponse response, long durationMs) {
        String text = "";
        String finishReason = "unknown";
        if (response != null && response.getResult() != null) {
            // Spring AI 1.1.x：AssistantMessage 使用 getText()（getContent() 已移除）
            if (response.getResult().getOutput() != null && response.getResult().getOutput().getText() != null) {
                text = response.getResult().getOutput().getText();
            }
            if (response.getResult().getMetadata() != null
                    && response.getResult().getMetadata().getFinishReason() != null) {
                finishReason = response.getResult().getMetadata().getFinishReason();
            }
        }
        int inputTokens = 0;
        int outputTokens = 0;
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            inputTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        }
        usageTracker.record(alias, inputTokens, outputTokens,
                runtime.inputPricePer1m(), runtime.outputPricePer1m());
        return new GatewayChatResult(alias, runtime.providerName(), runtime.modelId(), text,
                inputTokens, outputTokens, finishReason, durationMs, LocalDateTime.now());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static boolean isRateLimit(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return message.contains("429") || message.contains("rate limit") || message.contains("rate_limit");
    }

    /** 不可变快照：热重载时整体替换 */
    private record GatewayState(ModelGatewayConfig config, Map<String, ModelRuntime> runtimes) {

        ModelRuntime runtime(String alias) {
            ModelRuntime runtime = runtimes.get(alias);
            if (runtime == null) {
                throw new ModelGatewayException("未知模型别名: " + alias + "（可用: " + runtimes.keySet() + "）");
            }
            return runtime;
        }
    }

    /** 运行期模型条目：绑定已构建的 ChatModel */
    private record ModelRuntime(String alias, String providerName, String modelId, ProviderConfig provider,
                                ChatModel chatModel, int timeoutSeconds,
                                double inputPricePer1m, double outputPricePer1m,
                                Integer contextWindowTokens) {
    }

    /**
     * SDK 直连路径的用量记账装饰器（Agent/辩论/裁判/摘要经 {@link #chatModel(String)} 获取模型）：
     * call 逐次记账；stream 在完成时按末次响应的 usage 记一次（避免分块累计重复计数）。
     */
    private final class UsageTrackingChatModel implements ChatModel {

        private final ModelRuntime runtime;

        UsageTrackingChatModel(ModelRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = runtime.chatModel().call(prompt);
            recordUsage(usageOf(response));
            return response;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            final org.springframework.ai.chat.metadata.Usage[] last =
                    new org.springframework.ai.chat.metadata.Usage[1];
            return runtime.chatModel().stream(prompt)
                    .doOnNext(response -> {
                        var usage = usageOf(response);
                        if (usage != null) {
                            last[0] = usage;
                        }
                    })
                    .doOnComplete(() -> recordUsage(last[0]));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return runtime.chatModel().getDefaultOptions();
        }

        private void recordUsage(org.springframework.ai.chat.metadata.Usage usage) {
            if (usage == null) {
                return; // 响应无用量元数据（部分提供方不返回）：跳过记账
            }
            usageTracker.record(runtime.alias(),
                    usage.getPromptTokens() != null ? usage.getPromptTokens() : 0,
                    usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0,
                    runtime.inputPricePer1m(), runtime.outputPricePer1m());
        }

        private org.springframework.ai.chat.metadata.Usage usageOf(ChatResponse response) {
            if (response == null || response.getMetadata() == null) {
                return null;
            }
            return response.getMetadata().getUsage();
        }
    }
}
