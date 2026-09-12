package com.omniforge.core.debate;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SAA 并行辩论轮次执行器（Phase 2.2 无裁判模式）。
 *
 * <p>每轮动态构建 {@link ParallelAgent}（subAgents 按模型别名运行时创建，
 * v5.2 冻结方案），invoke 后从 OverAllState 逐键提取各模型发言。
 * 子 Agent 输出键前缀 {@code debate_}（别名全局唯一保证 outputKey 不冲突）。</p>
 */
public class SaaParallelDebateRoundExecutor implements DebateRoundExecutor {

    private static final Logger log = LoggerFactory.getLogger(SaaParallelDebateRoundExecutor.class);
    private static final String OUTPUT_KEY_PREFIX = "debate_";

    private final ReactAgentFactory reactAgentFactory;

    public SaaParallelDebateRoundExecutor(ReactAgentFactory reactAgentFactory) {
        this.reactAgentFactory = reactAgentFactory;
    }

    @Override
    public reactor.core.publisher.Flux<DebateRoundEvent> streamRound(List<String> aliases, String roundInput) {
        // 兼容路径：ParallelAgent 聚合完成后整体回放（Chunk + Finished 成对发出）
        return reactor.core.publisher.Flux.fromIterable(executeRound(aliases, roundInput).entrySet())
                .flatMap(entry -> reactor.core.publisher.Flux.just(
                        new DebateRoundExecutor.Chunk(entry.getKey(), entry.getValue()),
                        new DebateRoundExecutor.Finished(entry.getKey(), entry.getValue())));
    }

    @Override
    public Map<String, String> executeRound(List<String> aliases, String roundInput) {
        // 诊断日志：确认主题输入与实际输出（定位"辩论主题不符"类问题）
        log.info("辩论轮次输入（{} 字符）：{}", roundInput.length(),
                roundInput.length() > 200 ? roundInput.substring(0, 200) + "…" : roundInput);
        List<com.alibaba.cloud.ai.graph.agent.Agent> debaters = aliases.stream()
                .<com.alibaba.cloud.ai.graph.agent.Agent>map(
                        alias -> reactAgentFactory.createDebater(alias, OUTPUT_KEY_PREFIX + alias))
                .toList();

        ParallelAgent parallelAgent = ParallelAgent.builder()
                .name("debate_round")
                .description("多模型无裁判辩论（单轮并行 fan-out/gather）")
                .mergeOutputKey("debate_outputs")
                .subAgents(debaters)
                .build();

        Optional<OverAllState> state;
        try {
            state = parallelAgent.invoke(new UserMessage(roundInput));
        } catch (com.alibaba.cloud.ai.graph.exception.GraphRunnerException e) {
            // 风险 #3 对策：受检图执行异常统一转为运行时异常，由 DebateEngine 以 Failed 事件处理
            throw new IllegalStateException("SAA 图执行失败：" + e.getMessage(), e);
        }

        Map<String, String> results = new LinkedHashMap<>();
        aliases.forEach(alias -> {
            String text = extractText(state, OUTPUT_KEY_PREFIX + alias);
            results.put(alias, text);
            log.info("辩论输出 [{}]（{} 字符）：{}", alias, text.length(),
                    text.length() > 200 ? text.substring(0, 200) + "…" : text);
        });
        return results;
    }

    private static String extractText(Optional<OverAllState> state, String key) {
        if (state.isEmpty() || state.get().data() == null) {
            log.warn("辩论轮次返回空状态（key={}）", key);
            return "";
        }
        Object raw = state.get().data().get(key);
        if (raw instanceof AssistantMessage message) {
            return message.getText() == null ? "" : message.getText();
        }
        if (raw != null) {
            return raw.toString();
        }
        log.warn("状态中缺失子 Agent 输出（key={}）", key);
        return "";
    }
}
