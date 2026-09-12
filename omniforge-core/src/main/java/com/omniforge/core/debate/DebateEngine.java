package com.omniforge.core.debate;

import com.omniforge.core.agent.StopReason;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多模型辩论引擎（需求 4.3，v5.2 冻结方案的自研状态机部分）。
 *
 * <p>职责：轮次驱动（每轮经 {@link DebateRoundExecutor} 逐模型并行流式执行）、
 * 时间/手动打断熔断、语义共识熔断（2.3）、观点单调性检测（2.3）、
 * 有裁判模式（2.4：每轮裁判"总结归纳+裁决投票"，宣布获胜方/判定死锁/争议解决即终止）、
 * 辩论记录聚合（含裁判评语强制记录）、事件流输出。</p>
 */
public class DebateEngine {

    private static final String DEBATER_ROLE_PROMPT =
            "你是一名辩论辩手。请围绕主题发表你的观点：立场清晰、论据充分；"
                    + "若下面提供了此前各轮观点，请针对性地回应、补充或反驳。直接输出你的发言内容。";

    private static final String DISCUSSION_ROLE_PROMPT =
            "你是一名圆桌讨论参与者。请围绕主题发表你的看法：观点清晰、有理有据；"
                    + "若下面提供了此前各轮观点，可以赞同、补充或温和地提出不同意见。直接输出你的发言内容。";

    private static final String BRAINSTORM_ROLE_PROMPT =
            "你是一名头脑风暴参与者。请围绕主题尽可能多地提出新颖的创意与思路："
                    + "鼓励发散与跨界联想，暂不批判、不评判可行性。若下面提供了此前各轮想法，"
                    + "请在此基础上继续延伸或提出新方向。直接输出你的想法（可用列表）。";

    /** 有裁判模式的死锁判据：连续 2 轮无新观点（用户 2.4 设计确认） */
    private static final int JUDGE_MODE_STALEMATE_ROUNDS = 2;

    private final DebateRoundExecutor roundExecutor;
    private final ConsensusDetector consensusDetector; // 可为 null（共识检测关闭）
    private final JudgeEngine judgeEngine;             // 可为 null（无裁判）
    private final ConcurrentHashMap<String, AtomicBoolean> interrupts = new ConcurrentHashMap<>();

    public DebateEngine(DebateRoundExecutor roundExecutor) {
        this(roundExecutor, null, null);
    }

    /** @param consensusDetector 语义共识检测器；null 时共识熔断关闭 */
    public DebateEngine(DebateRoundExecutor roundExecutor, ConsensusDetector consensusDetector) {
        this(roundExecutor, consensusDetector, null);
    }

    /** @param judgeEngine 裁判引擎；null 或请求未指定裁判模型时为无裁判模式 */
    public DebateEngine(DebateRoundExecutor roundExecutor, ConsensusDetector consensusDetector,
                        JudgeEngine judgeEngine) {
        this.roundExecutor = Objects.requireNonNull(roundExecutor, "roundExecutor");
        this.consensusDetector = consensusDetector;
        this.judgeEngine = judgeEngine;
    }

    /** 以事件流执行辩论（多列流式渲染） */
    public Flux<DebateEvent> stream(DebateRequest request) {
        Objects.requireNonNull(request, "request");
        // 关键：Flux.create 的生成器在订阅线程同步运行——若在 UI 线程订阅会冻结界面，
        // 强制在弹性线程执行，事件经订阅方回线程消费（UI 侧用 Platform.runLater）
        return Flux.<DebateEvent>create(sink -> runDebate(request, sink))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private void runDebate(DebateRequest request, FluxSink<DebateEvent> sink) {
        interrupts.remove(request.sessionId());
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + request.timeoutSeconds() * 1_000_000_000L;
        List<DebateRoundResult> roundResults = new ArrayList<>();
        List<DebateResult.JudgeVerdictEntry> judgeVerdicts = new ArrayList<>();
        String winnerAlias = null;
        StringBuilder transcript = new StringBuilder();
        int stalemateStreak = 0; // 连续"无新内容"轮数（2.3 单调性检测）
        boolean judgeMode = judgeEngine != null
                && request.judgeAlias() != null && !request.judgeAlias().isBlank();

        try {
            for (int round = 1; round <= request.maxRounds(); round++) {
                if (sink.isCancelled()) {
                    return;
                }
                if (isInterrupted(request.sessionId())) {
                    sink.next(new DebateEvent.Completed(result(request, roundResults, judgeVerdicts, winnerAlias,
                            StopReason.INTERRUPTED, startNanos, null)));
                    return;
                }
                if (System.nanoTime() > deadlineNanos) {
                    sink.next(new DebateEvent.Completed(result(request, roundResults, judgeVerdicts, winnerAlias,
                            StopReason.TIMEOUT, startNanos, null)));
                    return;
                }

                sink.next(new DebateEvent.RoundStarted(round));
                String roundInput = buildRoundInput(request, transcript.toString());
                final int currentRound = round; // 循环变量不可被 lambda 捕获
                List<DebateRoundExecutor.Finished> collected =
                        new java.util.concurrent.CopyOnWriteArrayList<>();
                java.util.concurrent.CountDownLatch roundDone = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicReference<Throwable> roundError =
                        new java.util.concurrent.atomic.AtomicReference<>();

                // 逐模型并行流式执行：增量块即时转发（sink 线程安全），完成时聚合
                java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> roundSubscription =
                        new java.util.concurrent.atomic.AtomicReference<>();
                roundSubscription.set(roundExecutor.streamRound(request.modelAliases(), roundInput)
                        .doOnNext(roundEvent -> {
                            if (roundEvent instanceof DebateRoundExecutor.Chunk chunk) {
                                sink.next(new DebateEvent.ModelChunk(chunk.alias(), currentRound, chunk.text()));
                            } else if (roundEvent instanceof DebateRoundExecutor.Finished finished) {
                                collected.add(finished);
                                sink.next(new DebateEvent.ModelText(finished.alias(), currentRound,
                                        finished.fullText() == null ? "" : finished.fullText()));
                            }
                        })
                        .doOnError(roundError::set)
                        .doOnComplete(roundDone::countDown)
                        .subscribe());

                // 轮内中断轮询：500ms 粒度响应手动停止，无需等待本轮结束
                boolean roundFinished = false;
                while (!roundFinished) {
                    try {
                        roundFinished = roundDone.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        roundSubscription.get().dispose();
                        sink.next(new DebateEvent.Completed(result(request, roundResults, judgeVerdicts, winnerAlias,
                                StopReason.INTERRUPTED, startNanos, null)));
                        return;
                    }
                    if (!roundFinished && isInterrupted(request.sessionId())) {
                        roundSubscription.get().dispose();
                        sink.next(new DebateEvent.Completed(result(request, roundResults, judgeVerdicts, winnerAlias,
                                StopReason.INTERRUPTED, startNanos, null)));
                        return;
                    }
                }
                if (roundError.get() != null) {
                    sink.next(new DebateEvent.Failed("辩论轮次执行失败：" + roundError.get().getMessage()));
                    return;
                }
                if (isInterrupted(request.sessionId())) {
                    sink.next(new DebateEvent.Completed(result(request, roundResults, judgeVerdicts, winnerAlias,
                            StopReason.INTERRUPTED, startNanos, null)));
                    return;
                }

                Map<String, String> outputs = new java.util.LinkedHashMap<>();
                collected.forEach(finished -> outputs.put(finished.alias(), finished.fullText()));
                roundResults.add(new DebateRoundResult(currentRound, outputs, java.time.LocalDateTime.now()));
                String priorTranscript = transcript.toString();
                outputs.forEach((alias, text) ->
                        transcript.append("第").append(currentRound).append("轮 · ")
                                .append(alias).append("：").append(text).append('\n'));

                // ---- 2.3 语义共识熔断（争议已解决）：相似度 ≥ 阈值 → 自动终止 ----
                if (consensusDetector != null && request.consensusThreshold() != null
                        && outputs.size() >= DebateRequest.MIN_MODELS) {
                    Double score = consensusDetector.consensusScore(outputs);
                    if (score != null && score >= request.consensusThreshold()) {
                        sink.next(new DebateEvent.Completed(result(request, roundResults, judgeVerdicts, winnerAlias,
                                StopReason.CONSENSUS, startNanos, null)));
                        return;
                    }
                }

                // ---- 2.3 观点单调性检测（有裁判模式：连续 2 轮无新观点 → 判死锁）----
                double novelty = noveltyOf(joinRoundText(outputs), priorTranscript);
                stalemateStreak = novelty < request.stalemateNoveltyThreshold()
                        ? stalemateStreak + 1 : 0;
                int stalemateLimit = judgeMode ? JUDGE_MODE_STALEMATE_ROUNDS : request.stalemateRounds();
                if (stalemateStreak >= stalemateLimit) {
                    sink.next(new DebateEvent.Completed(result(request, roundResults, judgeVerdicts, winnerAlias,
                            judgeMode ? StopReason.JUDGE_DEADLOCK : StopReason.STALEMATE,
                            startNanos, null)));
                    return;
                }

                // ---- 2.4 有裁判模式：每轮裁判"总结归纳 + 裁决投票"（评语强制记录并上屏）----
                if (judgeMode) {
                    JudgeEngine.JudgeVerdict verdict;
                    try {
                        verdict = judgeEngine.judge(request.judgeAlias(), request.topic(),
                                currentRound, outputs, priorTranscript);
                    } catch (RuntimeException e) {
                        // 裁判异常不中断辩论：保守按继续处理，评语记录异常
                        verdict = JudgeEngine.JudgeVerdict.continueDebate("（裁判调用异常：" + e.getMessage() + "）");
                    }
                    judgeVerdicts.add(new DebateResult.JudgeVerdictEntry(currentRound, verdict.text()));
                    sink.next(new DebateEvent.JudgeVerdict(currentRound, verdict.text()));
                    if (verdict.decision() == JudgeEngine.JudgeDecision.WINNER) {
                        winnerAlias = verdict.winnerAlias();
                        sink.next(new DebateEvent.Completed(result(request, roundResults, judgeVerdicts, winnerAlias,
                                StopReason.JUDGE_WINNER, startNanos, null)));
                        return;
                    }
                    if (verdict.decision() == JudgeEngine.JudgeDecision.DEADLOCK) {
                        sink.next(new DebateEvent.Completed(result(request, roundResults, judgeVerdicts, winnerAlias,
                                StopReason.JUDGE_DEADLOCK, startNanos, null)));
                        return;
                    }
                }
            }
            sink.next(new DebateEvent.Completed(result(request, roundResults, judgeVerdicts, winnerAlias,
                    StopReason.MAX_ROUNDS, startNanos, null)));
        } catch (RuntimeException e) {
            sink.next(new DebateEvent.Failed(e.getMessage()));
        } finally {
            sink.complete();
        }
    }

    /** 打断指定会话的辩论（幂等） */
    public void cancel(String sessionId) {
        interrupts.computeIfAbsent(sessionId, key -> new AtomicBoolean()).set(true);
    }

    private boolean isInterrupted(String sessionId) {
        AtomicBoolean flag = interrupts.get(sessionId);
        return flag != null && flag.get();
    }

    private static String buildRoundInput(DebateRequest request, String transcript) {
        StringBuilder input = new StringBuilder();
        if (request.systemText() != null && !request.systemText().isBlank()) {
            input.append("【辩论背景与规则】\n").append(request.systemText()).append("\n\n");
        }
        input.append("【辩论主题】\n").append(request.topic()).append("\n\n");
        if (!transcript.isEmpty()) {
            input.append("【此前各轮观点】\n").append(transcript).append('\n');
        }
        input.append(switch (request.discussionMode() == null
                ? DebateRequest.MODE_DEBATE : request.discussionMode()) {
            case DebateRequest.MODE_DISCUSSION -> DISCUSSION_ROLE_PROMPT;
            case DebateRequest.MODE_BRAINSTORM -> BRAINSTORM_ROLE_PROMPT;
            default -> DEBATER_ROLE_PROMPT;
        });
        return input.toString();
    }

    private static DebateResult result(DebateRequest request, List<DebateRoundResult> rounds,
                                       List<DebateResult.JudgeVerdictEntry> judgeVerdicts, String winnerAlias,
                                       StopReason stopReason, long startNanos, String errorMessage) {
        return new DebateResult(request.sessionId(), request.topic(), request.modelAliases(),
                request.judgeAlias(), request.maxRounds(),
                request.discussionMode() == null ? DebateRequest.MODE_DEBATE : request.discussionMode(),
                List.copyOf(rounds), List.copyOf(judgeVerdicts),
                winnerAlias, stopReason, (System.nanoTime() - startNanos) / 1_000_000, errorMessage);
    }

    private static String joinRoundText(Map<String, String> outputs) {
        return String.join("\n", outputs.values());
    }

    /**
     * 新颖度：本轮文本中未出现于历史观点的 4-gram 占比（0~1）。
     * 中英文均适用；历史为空时新颖度为 1。
     */
    static double noveltyOf(String roundText, String priorTranscript) {
        if (roundText == null || roundText.isBlank()) {
            return 0;
        }
        List<String> current = ngrams(roundText);
        if (current.isEmpty()) {
            return 0;
        }
        if (priorTranscript == null || priorTranscript.isBlank()) {
            return 1;
        }
        java.util.Set<String> prior = new java.util.HashSet<>(ngrams(priorTranscript));
        long fresh = current.stream().filter(gram -> !prior.contains(gram)).count();
        return (double) fresh / current.size();
    }

    private static List<String> ngrams(String text) {
        List<String> grams = new ArrayList<>();
        String normalized = text.replaceAll("\\s+", "");
        for (int i = 0; i + 4 <= normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 4));
        }
        return grams;
    }
}
