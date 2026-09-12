package com.omniforge.core.gateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.stream.Collectors;

/**
 * 累计 Token 成本计量（需求 4.2 成本熔断的数据基础）。
 *
 * <p>线程安全；成本按 models.yml 定价估算（美元，仅作熔断参考，非账单依据）。</p>
 */
public final class UsageTracker {

    private final ConcurrentHashMap<String, Accumulator> byAlias = new ConcurrentHashMap<>();

    /**
     * 记录一次调用。
     *
     * @param alias            模型别名
     * @param inputTokens      本次输入 token
     * @param outputTokens     本次输出 token
     * @param inputPricePer1m  输入定价（美元/百万 token）
     * @param outputPricePer1m 输出定价（美元/百万 token）
     */
    public void record(String alias, int inputTokens, int outputTokens,
                       double inputPricePer1m, double outputPricePer1m) {
        double cost = inputTokens / 1_000_000.0 * inputPricePer1m
                + outputTokens / 1_000_000.0 * outputPricePer1m;
        byAlias.computeIfAbsent(alias, k -> new Accumulator()).add(inputTokens, outputTokens, cost);
    }

    /** 全量快照 */
    public GatewayUsage snapshot() {
        Map<String, TokenUsage> usage = byAlias.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().toUsage()));
        long calls = 0;
        long input = 0;
        long output = 0;
        double cost = 0;
        for (TokenUsage u : usage.values()) {
            calls += u.calls();
            input += u.inputTokens();
            output += u.outputTokens();
            cost += u.estimatedCostUsd();
        }
        return new GatewayUsage(usage, calls, input, output, cost);
    }

    private static final class Accumulator {

        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final DoubleAdder costUsd = new DoubleAdder();

        void add(int input, int output, double cost) {
            calls.incrementAndGet();
            inputTokens.addAndGet(input);
            outputTokens.addAndGet(output);
            costUsd.add(cost);
        }

        TokenUsage toUsage() {
            return new TokenUsage(calls.get(), inputTokens.get(), outputTokens.get(), costUsd.sum());
        }
    }
}
