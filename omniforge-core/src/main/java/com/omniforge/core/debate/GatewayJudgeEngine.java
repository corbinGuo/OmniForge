package com.omniforge.core.debate;

import com.omniforge.core.gateway.GatewayChatResult;
import com.omniforge.core.gateway.GatewayRequest;
import com.omniforge.core.gateway.ModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网关裁判引擎（需求 4.3）：裁判模型经模型网关调用，
 * 提示词要求输出结构化裁决标记，解析失败时保守返回"继续"。
 */
public class GatewayJudgeEngine implements JudgeEngine {

    private static final Logger log = LoggerFactory.getLogger(GatewayJudgeEngine.class);

    private static final Pattern WINNER_PATTERN = Pattern.compile("【裁决】\\s*获胜方[：:]\\s*([^\\s，。,]+)");
    private static final Pattern DEADLOCK_PATTERN = Pattern.compile("【裁决】\\s*死锁");
    private static final Pattern CONTINUE_PATTERN = Pattern.compile("【裁决】\\s*继续");

    private final ModelGateway modelGateway;

    public GatewayJudgeEngine(ModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    @Override
    public JudgeVerdict judge(String judgeAlias, String topic, int round,
                              Map<String, String> roundOutputs, String historySummary) {
        String prompt = buildJudgePrompt(topic, round, roundOutputs, historySummary);
        try {
            GatewayChatResult result = modelGateway.chat(
                    new GatewayRequest(judgeAlias, null, prompt, null, null));
            return parseVerdict(result.text());
        } catch (Exception e) {
            log.warn("裁判调用失败（本轮保守继续）：{}", e.getMessage());
            return JudgeVerdict.continueDebate("（裁判调用失败：" + e.getMessage() + "）");
        }
    }

    /**
     * 解析裁决标记（包级可见便于测试）。
     * 无合法标记时保守返回"继续"。
     */
    static JudgeVerdict parseVerdict(String judgeText) {
        if (judgeText == null || judgeText.isBlank()) {
            return JudgeVerdict.continueDebate(judgeText);
        }
        Matcher winner = WINNER_PATTERN.matcher(judgeText);
        if (winner.find()) {
            return JudgeVerdict.winner(winner.group(1).trim(), judgeText);
        }
        if (DEADLOCK_PATTERN.matcher(judgeText).find()) {
            return JudgeVerdict.deadlock(judgeText);
        }
        if (CONTINUE_PATTERN.matcher(judgeText).find()) {
            return JudgeVerdict.continueDebate(judgeText);
        }
        log.warn("裁判输出缺少裁决标记，保守按继续处理");
        return JudgeVerdict.continueDebate(judgeText);
    }

    private static String buildJudgePrompt(String topic, int round, Map<String, String> roundOutputs,
                                           String historySummary) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是多模型辩论的裁判。辩论主题：「").append(topic).append("」。\n\n");
        if (historySummary != null && !historySummary.isBlank()) {
            prompt.append("【此前各轮观点摘要】\n").append(historySummary).append("\n\n");
        }
        prompt.append("【第 ").append(round).append(" 轮各方观点】\n");
        roundOutputs.forEach((alias, text) ->
                prompt.append("◆ ").append(alias).append("：\n").append(text).append("\n\n"));
        prompt.append("请完成两项任务：\n")
                .append("1. 对各模型观点进行总结归纳（每方 1~2 句）；\n")
                .append("2. 裁决投票：若已可判定胜负（论据/逻辑明显占优），宣布获胜方；"
                        + "若各方陷入重复且无进展，判定死锁；否则裁定继续。\n")
                .append("输出最后一行必须为裁决标记（三选一）：\n")
                .append("【裁决】获胜方：<模型别名>\n")
                .append("【裁决】死锁\n")
                .append("【裁决】继续\n");
        return prompt.toString();
    }
}
