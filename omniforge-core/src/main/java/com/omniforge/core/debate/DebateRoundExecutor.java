package com.omniforge.core.debate;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 单轮辩论执行器 SPI（Phase 2.2 无裁判模式）。
 *
 * <p>默认实现 {@code SaaStreamingDebateRoundExecutor}：每模型独立并行、逐 token 流式；
 * {@code SaaParallelDebateRoundExecutor}（ParallelAgent 聚合）为兼容实现。
 * 事件模型：{@link Chunk}（增量文本）与 {@link Finished}（该模型本轮完成，携带全文）。</p>
 */
public interface DebateRoundExecutor {

    /** 轮执行事件 */
    sealed interface DebateRoundEvent permits Chunk, Finished {
    }

    /** 某模型的一批增量文本 */
    record Chunk(String alias, String text) implements DebateRoundEvent {
    }

    /** 某模型本轮完成（携带完整发言文本，供记录/共识判定） */
    record Finished(String alias, String fullText) implements DebateRoundEvent {
    }

    /**
     * 流式执行一轮：各模型并行，增量文本即时发出，完成时发 {@link Finished}。
     */
    Flux<DebateRoundEvent> streamRound(List<String> aliases, String roundInput);

    /**
     * 阻塞执行一轮（兼容路径）：聚合各模型完整发言。
     */
    default Map<String, String> executeRound(List<String> aliases, String roundInput) {
        Map<String, String> results = new java.util.LinkedHashMap<>();
        streamRound(aliases, roundInput)
                .toIterable()
                .forEach(event -> {
                    if (event instanceof Finished finished) {
                        results.put(finished.alias(), finished.fullText());
                    }
                });
        return results;
    }
}
