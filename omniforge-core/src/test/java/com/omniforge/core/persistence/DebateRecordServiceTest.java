package com.omniforge.core.persistence;

import com.omniforge.core.agent.StopReason;
import com.omniforge.core.debate.DebateResult;
import com.omniforge.core.debate.DebateRoundResult;
import com.omniforge.core.persistence.entity.DebateRecord;
import com.omniforge.core.persistence.repository.DebateRecordRepository;
import com.omniforge.core.persistence.repository.MessageRepository;
import com.omniforge.core.persistence.repository.SessionRepository;
import com.omniforge.core.persistence.service.DebateRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 辩论记录落库/导出/重播集成测试（SQLite :memory:）。 */
class DebateRecordServiceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PersistenceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    JpaRepositoriesAutoConfiguration.class,
                    TransactionAutoConfiguration.class))
            .withPropertyValues("omniforge.persistence.database-file=:memory:");

    @Test
    void 落库后导出与重播正确() {
        contextRunner.run(context -> {
            DebateRecordService service = context.getBean(DebateRecordService.class);
            DebateRecordRepository recordRepository = context.getBean(DebateRecordRepository.class);
            SessionRepository sessionRepository = context.getBean(SessionRepository.class);
            MessageRepository messageRepository = context.getBean(MessageRepository.class);

            DebateResult result = sampleResult();
            DebateRecord record = service.save(result);

            // DebateRecord 归档
            assertEquals("测试主题", record.getTopic());
            assertEquals("a,b", record.getModelAliases());
            assertEquals("j", record.getJudgeAlias());
            assertEquals(StopReason.JUDGE_WINNER.name(), record.getStopReason());
            assertEquals("a", record.getWinnerAlias());
            // Session 复用
            assertEquals(1, sessionRepository.count());
            // Message 复用：2 轮 × 2 辩手 + 2 裁判评语 = 6 条
            assertEquals(6, messageRepository.count());
            // 历史列表
            assertEquals(1, recordRepository.findAllByOrderByStartedAtDesc().size());

            // Markdown：轮次标记 + 角色/模型分块 + 时间戳
            String md = service.exportMarkdown(record);
            assertTrue(md.contains("# 辩论记录"));
            assertTrue(md.contains("## 第 1 轮"), "应含轮次标记");
            assertTrue(md.contains("### a"), "应按模型分块");
            assertTrue(md.contains("### b"));
            assertTrue(md.contains("⚖ 裁判评语"), "应含裁判评语");
            assertTrue(md.contains("2026-"), "应含时间戳");

            // HTML：基础可读
            String html = service.exportHtml(record);
            assertTrue(html.contains("<h2>第 1 轮"), "HTML 应含轮次标题");
            assertTrue(html.contains("<pre>"), "HTML 应含正文块");

            // 重播：按时间顺序逐条（每轮：分隔 + 2 辩手 + 裁判 = 4 条 × 2 轮）
            List<DebateRecordService.ReplayItem> replay = service.replay(record);
            assertEquals(8, replay.size());
            assertEquals("system", replay.get(0).role());
            assertEquals("assistant", replay.get(1).role());
            assertEquals("judge", replay.get(3).role());
            assertEquals(replay.get(0).at(), replay.get(0).at());
            for (int i = 1; i < replay.size(); i++) {
                assertTrue(replay.get(i).seq() > replay.get(i - 1).seq(), "顺序递增");
            }
        });
    }

    private static DebateResult sampleResult() {
        LocalDateTime round1Time = LocalDateTime.of(2026, 8, 27, 12, 0, 0);
        LocalDateTime round2Time = round1Time.plusMinutes(2);
        Map<String, String> outputs1 = new LinkedHashMap<>();
        outputs1.put("a", "我认为方案一更优。");
        outputs1.put("b", "我反对，方案二更稳健。");
        Map<String, String> outputs2 = new LinkedHashMap<>();
        outputs2.put("a", "方案一成本更低。");
        outputs2.put("b", "但方案二风险更小。");
        List<DebateRoundResult> rounds = List.of(
                new DebateRoundResult(1, outputs1, round1Time),
                new DebateRoundResult(2, outputs2, round2Time));
        List<DebateResult.JudgeVerdictEntry> verdicts = List.of(
                new DebateResult.JudgeVerdictEntry(1, "第一轮总结与裁决。"),
                new DebateResult.JudgeVerdictEntry(2, "第二轮：a 方胜出。\n【裁决】获胜方：a"));
        return new DebateResult("s1", "测试主题", List.of("a", "b"), "j", 3, "debate",
                rounds, verdicts, "a", StopReason.JUDGE_WINNER, 120_000, null);
    }
}
