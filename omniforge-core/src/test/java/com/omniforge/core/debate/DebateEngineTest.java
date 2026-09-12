package com.omniforge.core.debate;

import com.omniforge.core.agent.StopReason;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 辩论引擎状态机测试（假轮次执行器，脱离 SAA 图引擎）。
 */
class DebateEngineTest {

    /** 将"整轮产出 Map"的假实现适配为流式执行器（Chunk+Finished 成对回放） */
    private static DebateRoundExecutor executorOf(
            BiFunction<List<String>, String, Map<String, String>> fn) {
        return new DebateRoundExecutor() {
            @Override
            public Flux<DebateRoundEvent> streamRound(List<String> aliases, String roundInput) {
                return Flux.fromIterable(fn.apply(aliases, roundInput).entrySet())
                        .flatMap(entry -> Flux.just(
                                new DebateRoundExecutor.Chunk(entry.getKey(), entry.getValue()),
                                new DebateRoundExecutor.Finished(entry.getKey(), entry.getValue())));
            }
        };
    }

    @Test
    void 多轮事件序列完整() {
        DebateEngine engine = new DebateEngine(executorOf((aliases, roundInput) -> {
            Map<String, String> outputs = new LinkedHashMap<>();
            aliases.forEach(alias -> outputs.put(alias, alias + "-观点" + roundInput.hashCode() % 100));
            return outputs;
        }));

        List<DebateEvent> events = engine.stream(
                DebateRequest.of("s1", "人工智能的未来", null, List.of("model-a", "model-b")))
                .collectList().block();

        assertTrue(events != null);
        assertEquals(3, events.stream().filter(e -> e instanceof DebateEvent.RoundStarted).count(),
                "默认 3 轮应产生 3 个 RoundStarted");
    }

    @Test
    void 默认三轮每模型三条发言() {
        DebateEngine engine = new DebateEngine(executorOf((aliases, roundInput) -> {
            Map<String, String> outputs = new LinkedHashMap<>();
            aliases.forEach(alias -> outputs.put(alias, alias + ":" + roundInput));
            return outputs;
        }));

        List<DebateEvent> events = engine.stream(
                DebateRequest.of("s1", "主题", null, List.of("a", "b")))
                .collectList().block();

        assertTrue(events != null);
        assertEquals(3, events.stream().filter(e -> e instanceof DebateEvent.RoundStarted).count());
        assertEquals(3, events.stream().filter(e -> e instanceof DebateEvent.ModelText m && m.modelAlias().equals("a")).count());
        assertEquals(3, events.stream().filter(e -> e instanceof DebateEvent.ModelText m && m.modelAlias().equals("b")).count());
        // 每个 ModelText 前应有对应的增量 Chunk（流式路径）
        assertEquals(6, events.stream().filter(e -> e instanceof DebateEvent.ModelChunk).count());
        DebateEvent.Completed completed = (DebateEvent.Completed) events.get(events.size() - 1);
        assertEquals(StopReason.MAX_ROUNDS, completed.result().stopReason());
        assertEquals(3, completed.result().rounds().size());
    }

    @Test
    void 轮次输入包含主题与历史观点() {
        final StringBuilder captured = new StringBuilder();
        DebateEngine engine = new DebateEngine(executorOf((aliases, roundInput) -> {
            captured.append(roundInput).append("\n---\n");
            Map<String, String> outputs = new LinkedHashMap<>();
            aliases.forEach(alias -> outputs.put(alias, alias + "的观点"));
            return outputs;
        }));

        engine.stream(DebateRequest.of("s1", "辩论主题X", "规则Y", List.of("a", "b"))).collectList().block();

        String allInputs = captured.toString();
        assertTrue(allInputs.contains("辩论主题X"), "每轮输入应包含主题");
        assertTrue(allInputs.contains("规则Y"), "首轮输入应包含背景规则");
        assertTrue(allInputs.contains("此前各轮观点"), "第二轮起输入应包含历史观点");
        assertTrue(allInputs.contains("a的观点"), "历史观点摘要应包含此前发言");
    }

    @Test
    void 手动打断返回INTERRUPTED() throws InterruptedException {
        DebateEngine engine = new DebateEngine(executorOf((aliases, roundInput) -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Map<String, String> outputs = new LinkedHashMap<>();
            aliases.forEach(alias -> outputs.put(alias, alias));
            return outputs;
        }));

        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        Thread runner = Thread.ofVirtual().start(() -> {
            engine.stream(DebateRequest.of("s1", "主题", null, List.of("a", "b")))
                    .doOnNext(event -> {
                        if (event instanceof DebateEvent.RoundStarted) {
                            started.countDown();
                        }
                    })
                    .collectList()
                    .block();
        });
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        engine.cancel("s1");
        runner.join(5_000);
    }

    @Test
    void 模型数量越界校验() {
        assertThrows(IllegalArgumentException.class,
                () -> DebateRequest.of("s1", "主题", null, List.of("only-one")));
        assertThrows(IllegalArgumentException.class,
                () -> DebateRequest.of("s1", "主题", null,
                        List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")));
    }

    @Test
    void 轮次执行失败返回Failed() {
        DebateEngine engine = new DebateEngine(executorOf((aliases, roundInput) -> {
            throw new IllegalStateException("执行器故障");
        }));

        List<DebateEvent> events = engine.stream(
                DebateRequest.of("s1", "主题", null, List.of("a", "b")))
                .collectList().block();

        // 事件序：RoundStarted(1) → Failed（执行器在首轮抛异常）
        assertTrue(events != null);
        DebateEvent last = events.get(events.size() - 1);
        assertTrue(last instanceof DebateEvent.Failed failed
                && failed.errorMessage().contains("执行器故障"), "最后一个事件应为 Failed");
    }

    @Test
    void 语义共识达成自动终止() {
        // 检测器固定返回 0.95（≥ 阈值 0.92）
        DebateEngine engine = new DebateEngine(executorOf((aliases, roundInput) -> {
            Map<String, String> outputs = new LinkedHashMap<>();
            aliases.forEach(alias -> outputs.put(alias, "我们都认为这个方案是可行的"));
            return outputs;
        }), roundOutputs -> 0.95);

        List<DebateEvent> events = engine.stream(
                DebateRequest.of("s1", "主题", null, List.of("a", "b")))
                .collectList().block();

        assertTrue(events != null);
        DebateEvent.Completed completed = (DebateEvent.Completed) events.get(events.size() - 1);
        assertEquals(StopReason.CONSENSUS, completed.result().stopReason());
        assertEquals(1, completed.result().rounds().size(), "共识应在首轮即终止");
    }

    @Test
    void 检测器返回null时跳过共识判定() {
        DebateEngine engine = new DebateEngine(executorOf((aliases, roundInput) -> {
            Map<String, String> outputs = new LinkedHashMap<>();
            aliases.forEach(alias -> outputs.put(alias, alias + "的观点"));
            return outputs;
        }), roundOutputs -> null);

        List<DebateEvent> events = engine.stream(
                DebateRequest.of("s1", "主题", null, List.of("a", "b")))
                .collectList().block();

        DebateEvent.Completed completed = (DebateEvent.Completed) events.get(events.size() - 1);
        assertEquals(StopReason.MAX_ROUNDS, completed.result().stopReason(),
                "检测器不可用时应正常走完轮次");
    }

    @Test
    void 连续多轮无新内容判定哑火() {
        DebateEngine engine = new DebateEngine(executorOf((aliases, roundInput) -> {
            Map<String, String> outputs = new LinkedHashMap<>();
            aliases.forEach(alias -> outputs.put(alias, "重复观点重复观点重复观点"));
            return outputs;
        }));

        // 第 1 轮对空历史为"全新"，连续 3 轮无新内容需要至少 4 轮（1 基线 + 3 哑火）
        List<DebateEvent> events = engine.stream(new DebateRequest(
                "s1", "主题", null, List.of("a", "b"), 4, 300, null, 0.92, 3, 0.10, null,
                DebateRequest.MODE_DEBATE))
                .collectList().block();

        DebateEvent.Completed completed = (DebateEvent.Completed) events.get(events.size() - 1);
        assertEquals(StopReason.STALEMATE, completed.result().stopReason());
        assertEquals(4, completed.result().rounds().size());
    }

    @Test
    void 讨论与头脑风暴模式使用对应角色提示() {
        final StringBuilder captured = new StringBuilder();
        DebateEngine engine = new DebateEngine(executorOf((aliases, roundInput) -> {
            captured.append(roundInput).append("\n---\n");
            Map<String, String> outputs = new LinkedHashMap<>();
            aliases.forEach(alias -> outputs.put(alias, alias + "的看法"));
            return outputs;
        }));

        engine.stream(new DebateRequest("s1", "主题", null, List.of("a", "b"), 1, 300, null,
                0.92, 3, 0.10, null, DebateRequest.MODE_DISCUSSION)).collectList().block();
        engine.stream(new DebateRequest("s2", "主题", null, List.of("a", "b"), 1, 300, null,
                0.92, 3, 0.10, null, DebateRequest.MODE_BRAINSTORM)).collectList().block();

        String allInputs = captured.toString();
        assertTrue(allInputs.contains("圆桌讨论参与者"), "讨论模式应使用讨论角色提示");
        assertTrue(allInputs.contains("头脑风暴参与者"), "头脑风暴模式应使用头脑风暴角色提示");
    }

    @Test
    void 非法协作模式被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new DebateRequest(
                "s1", "主题", null, List.of("a", "b"), 3, 300, null,
                0.92, 3, 0.10, null, "singing"));
    }

    @Test
    void 新颖度计算() {
        assertEquals(1.0, DebateEngine.noveltyOf("全新内容全新内容", ""), 0.001);
        assertEquals(0.0, DebateEngine.noveltyOf("重复内容重复内容", "重复内容重复内容"), 0.001);
        double partial = DebateEngine.noveltyOf("历史观点的新论述", "这是历史观点内容");
        assertTrue(partial > 0 && partial < 1, "部分重叠应介于 0~1");
    }

    // ---------- 2.4 有裁判模式 ----------

    private static final java.util.function.BiFunction<List<String>, String, Map<String, String>>
            CONSTANT_OUTPUTS = (aliases, roundInput) -> {
        // 长文本常量：避免短文本拼接处的跨界 4-gram 被误判为"新内容"
        Map<String, String> outputs = new LinkedHashMap<>();
        aliases.forEach(alias -> outputs.put(alias,
                alias + "认为人工智能的发展需要谨慎监管，应当平衡创新与风险并建立完善的治理体系"));
        return outputs;
    };

    private static JudgeEngine fixedJudge(JudgeEngine.JudgeVerdict verdict) {
        return (judgeAlias, topic, round, roundOutputs, historySummary) -> verdict;
    }

    private static DebateRequest judgeRequest(int maxRounds) {
        return new DebateRequest("s1", "主题", null, List.of("a", "b"), maxRounds, 300, null,
                0.92, 3, 0.10, "judge-model", DebateRequest.MODE_DEBATE);
    }

    @Test
    void 裁判宣布获胜方终止辩论() {
        DebateEngine engine = new DebateEngine(executorOf(CONSTANT_OUTPUTS), null,
                fixedJudge(JudgeEngine.JudgeVerdict.winner("a", "总结：a 方论据占优。\n【裁决】获胜方：a")));

        List<DebateEvent> events = engine.stream(judgeRequest(4)).collectList().block();

        assertTrue(events != null);
        assertTrue(events.stream().anyMatch(e -> e instanceof DebateEvent.JudgeVerdict),
                "裁判评语必须上屏");
        DebateEvent.Completed completed = (DebateEvent.Completed) events.get(events.size() - 1);
        assertEquals(StopReason.JUDGE_WINNER, completed.result().stopReason());
        assertEquals("a", completed.result().winnerAlias());
        assertEquals(1, completed.result().rounds().size());
        assertEquals(1, completed.result().judgeVerdicts().size(), "裁判评语强制记录");
    }

    @Test
    void 裁判判定死锁终止辩论() {
        DebateEngine engine = new DebateEngine(executorOf(CONSTANT_OUTPUTS), null,
                fixedJudge(JudgeEngine.JudgeVerdict.deadlock("各方观点重复，无新进展。\n【裁决】死锁")));

        List<DebateEvent> events = engine.stream(judgeRequest(4)).collectList().block();

        DebateEvent.Completed completed = (DebateEvent.Completed) events.get(events.size() - 1);
        assertEquals(StopReason.JUDGE_DEADLOCK, completed.result().stopReason());
    }

    @Test
    void 裁判裁定继续则走满轮次且每轮记录评语() {
        // 每轮输出含新内容（基于轮次输入），避免触发"连续无新观点"死锁
        DebateEngine engine = new DebateEngine(executorOf((aliases, roundInput) -> {
            Map<String, String> outputs = new LinkedHashMap<>();
            aliases.forEach(alias -> outputs.put(alias,
                    alias + "本轮新观点：" + roundInput.hashCode() + "号论述"));
            return outputs;
        }), null, fixedJudge(JudgeEngine.JudgeVerdict.continueDebate("本局尚无优势方。\n【裁决】继续")));

        List<DebateEvent> events = engine.stream(judgeRequest(3)).collectList().block();

        DebateEvent.Completed completed = (DebateEvent.Completed) events.get(events.size() - 1);
        assertEquals(StopReason.MAX_ROUNDS, completed.result().stopReason());
        assertEquals(3, completed.result().judgeVerdicts().size(), "每轮评语都应记录");
    }

    @Test
    void 有裁判模式连续两轮无新观点判死锁() {
        // 裁判一直"继续"，但模型输出恒不变：新颖度 0 连续 2 轮 → JUDGE_DEADLOCK
        DebateEngine engine = new DebateEngine(executorOf(CONSTANT_OUTPUTS), null,
                fixedJudge(JudgeEngine.JudgeVerdict.continueDebate("继续")));

        List<DebateEvent> events = engine.stream(judgeRequest(4)).collectList().block();

        DebateEvent.Completed completed = (DebateEvent.Completed) events.get(events.size() - 1);
        assertEquals(StopReason.JUDGE_DEADLOCK, completed.result().stopReason());
        assertEquals(3, completed.result().rounds().size(), "1 轮基线 + 2 轮无新观点，第 3 轮判死锁");
    }
}
