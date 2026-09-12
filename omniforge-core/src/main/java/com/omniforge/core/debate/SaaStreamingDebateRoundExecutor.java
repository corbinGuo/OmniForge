package com.omniforge.core.debate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 逐模型并行、逐 token 流式的辩论轮次执行器（Phase 2.2 默认实现）。
 *
 * <p>设计（2026-08-27）：SAA 图引擎的 NodeOutput 仅为节点完成时的状态快照，
 * 无增量文本；为满足"逐 token 渲染"的 UX 要求，执行层直接使用各模型的
 * {@code ChatModel.stream()}（角色提示已由 DebateEngine 织入轮次输入），
 * 每模型一个弹性线程并行订阅——先答先显，异常只影响自身。
 * ParallelAgent 聚合路径保留于 {@link SaaParallelDebateRoundExecutor}。</p>
 */
public class SaaStreamingDebateRoundExecutor implements DebateRoundExecutor {

    private static final Logger log = LoggerFactory.getLogger(SaaStreamingDebateRoundExecutor.class);

    private final ReactAgentFactory reactAgentFactory;

    public SaaStreamingDebateRoundExecutor(ReactAgentFactory reactAgentFactory) {
        this.reactAgentFactory = reactAgentFactory;
    }

    @Override
    public Flux<DebateRoundExecutor.DebateRoundEvent> streamRound(List<String> aliases, String roundInput) {
        List<Flux<DebateRoundExecutor.DebateRoundEvent>> perModel = aliases.stream()
                .map(alias -> streamOne(alias, roundInput)
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(e -> {
                            String error = "（调用失败：" + e.getMessage() + "）";
                            log.warn("辩论模型 {} 调用失败：{}", alias, e.getMessage());
                            return Flux.just(
                                    new DebateRoundExecutor.Chunk(alias, error),
                                    new DebateRoundExecutor.Finished(alias, error));
                        }))
                .toList();
        return Flux.merge(perModel);
    }

    private Flux<DebateRoundExecutor.DebateRoundEvent> streamOne(String alias, String roundInput) {
        ChatModel chatModel = reactAgentFactory.chatModel(alias);
        Prompt prompt = new Prompt(new UserMessage(roundInput));
        AtomicReference<StringBuilder> accumulated = new AtomicReference<>(new StringBuilder());
        return chatModel.stream(prompt)
                .map(response -> extractText(response))
                .filter(text -> text != null && !text.isEmpty())
                .doOnNext(text -> accumulated.get().append(text))
                .map(text -> (DebateRoundExecutor.DebateRoundEvent) new DebateRoundExecutor.Chunk(alias, text))
                .concatWith(Mono.fromCallable(() -> {
                    String full = accumulated.get().toString();
                    log.info("辩论输出 [{}]（{} 字符）", alias, full.length());
                    return (DebateRoundExecutor.DebateRoundEvent) new DebateRoundExecutor.Finished(alias, full);
                }));
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }
}
