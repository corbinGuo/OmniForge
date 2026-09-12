package com.omniforge.core.context;

import com.omniforge.core.gateway.GatewayChatResult;
import com.omniforge.core.gateway.GatewayRequest;
import com.omniforge.core.gateway.ModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于模型网关的 {@link HistorySummarizer} 实现：
 * 把被裁剪的历史轮对压缩为 ≤600 字中文摘要（调用摘要模型一次）。
 *
 * <p>摘要模型别名来自 {@link ContextSettings#summarizeModelAlias}（null = 当前默认模型）；
 * 调用异常由 {@link DefaultContextManager} 包装降级为纯滑动窗口。</p>
 */
final class GatewayHistorySummarizer implements HistorySummarizer {

    private static final Logger log = LoggerFactory.getLogger(GatewayHistorySummarizer.class);
    /** 摘要长度上限（字符） */
    static final int MAX_SUMMARY_CHARS = 600;
    private static final String SYSTEM_PROMPT =
            "你是对话记录摘要助手。请把下面的对话记录压缩为不超过" + MAX_SUMMARY_CHARS
                    + "字的中文摘要，必须保留具体事实（数字、名称、代码、用户需求与已确认结论），"
                    + "只输出摘要正文，不要任何其他内容。";

    private final ModelGateway modelGateway;
    private final ContextSettingsHolder settingsHolder;

    GatewayHistorySummarizer(ModelGateway modelGateway, ContextSettingsHolder settingsHolder) {
        this.modelGateway = modelGateway;
        this.settingsHolder = settingsHolder;
    }

    @Override
    public String summarize(List<ContextEntry> dropped) {
        String alias = settingsHolder.current().summarizeModelAlias();
        if (alias == null || alias.isBlank()) {
            alias = modelGateway.resolveAlias(null);
        }
        String transcript = dropped.stream()
                .map(entry -> (entry.role() == ContextRole.USER ? "用户：" : "助手：") + entry.content())
                .collect(Collectors.joining("\n"));
        log.info("上下文摘要调用：模型={}，摘要 {} 轮对话", alias, dropped.size() / 2);
        GatewayChatResult result = modelGateway.chat(new GatewayRequest(alias, SYSTEM_PROMPT, transcript, null, null));
        String summary = result.text() == null ? "" : result.text().trim();
        return summary.length() > MAX_SUMMARY_CHARS ? summary.substring(0, MAX_SUMMARY_CHARS) : summary;
    }
}
