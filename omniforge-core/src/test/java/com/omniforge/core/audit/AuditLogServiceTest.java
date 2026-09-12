package com.omniforge.core.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计日志服务测试：JSONL 按天分文件、时间范围查询、损坏行跳过、禁用开关 */
class AuditLogServiceTest {

    @TempDir
    Path tempDir;

    private AuditLogService service;

    @BeforeEach
    void setUp() {
        AuditProperties properties = new AuditProperties();
        properties.setDirectory(tempDir.resolve("logs").resolve("audit"));
        service = new AuditLogService(properties);
    }

    private AuditEntry entry(Instant timestamp, String runId, long inputTokens) {
        return new AuditEntry(timestamp, "tester", "ui:default", runId, "test-model", "agent",
                "你好，请分析", "COMPLETED", 1200, inputTokens, 300, 0.001,
                List.of(new AuditEntry.AuditToolCall("web_search", "{\"q\":\"x\"}", "结果", "SUCCESS", 42)));
    }

    @Test
    void 记录落盘为按天JSONL且字段往返一致() throws Exception {
        Instant ts = Instant.parse("2026-08-29T10:00:00Z");
        service.record(entry(ts, "run-1", 100));

        Path file = tempDir.resolve("logs").resolve("audit").resolve("audit-2026-08-29.jsonl");
        assertThat(Files.isRegularFile(file)).isTrue();
        AuditEntry loaded = service.query(ts.minusSeconds(1), ts.plusSeconds(1)).get(0);
        assertThat(loaded.runId()).isEqualTo("run-1");
        assertThat(loaded.sessionId()).isEqualTo("ui:default");
        assertThat(loaded.modelAlias()).isEqualTo("test-model");
        assertThat(loaded.inputTokens()).isEqualTo(100);
        assertThat(loaded.toolCalls()).hasSize(1);
        assertThat(loaded.toolCalls().get(0).toolName()).isEqualTo("web_search");
        assertThat(loaded.toolCalls().get(0).status()).isEqualTo("SUCCESS");
    }

    @Test
    void 跨天记录分文件且时间范围查询只命中目标日() {
        Instant day1 = Instant.parse("2026-08-28T10:00:00Z");
        Instant day2 = Instant.parse("2026-08-29T10:00:00Z");
        service.record(entry(day1, "run-old", 1));
        service.record(entry(day2, "run-new", 2));

        List<AuditEntry> hits = service.query(day2.minusSeconds(1), day2.plusSeconds(1));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).runId()).isEqualTo("run-new");
        // 跨两天查询按时间升序
        assertThat(service.query(day1.minusSeconds(1), day2.plusSeconds(1)))
                .extracting(AuditEntry::runId)
                .containsExactly("run-old", "run-new");
    }

    @Test
    void 损坏行跳过不影响其余记录() throws Exception {
        Instant ts = Instant.parse("2026-08-29T10:00:00Z");
        service.record(entry(ts, "run-ok", 1));
        String day = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(ts.atZone(ZoneOffset.UTC).toLocalDate());
        Files.writeString(tempDir.resolve("logs").resolve("audit").resolve("audit-" + day + ".jsonl"),
                "{{{ broken line\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        assertThat(service.query(ts.minusSeconds(1), ts.plusSeconds(1)))
                .extracting(AuditEntry::runId).containsExactly("run-ok");
    }

    @Test
    void 目录缺失时查询返回空() {
        AuditProperties properties = new AuditProperties();
        properties.setDirectory(tempDir.resolve("nope"));
        assertThat(new AuditLogService(properties).query(
                Instant.now().minusSeconds(60), Instant.now())).isEmpty();
    }

    @Test
    void 禁用时不写盘也不查询() {
        AuditProperties properties = new AuditProperties();
        properties.setDirectory(tempDir.resolve("logs").resolve("audit"));
        properties.setEnabled(false);
        AuditLogService disabled = new AuditLogService(properties);
        disabled.record(entry(Instant.now(), "run-x", 1));
        assertThat(tempDir.resolve("logs").resolve("audit")).doesNotExist();
        assertThat(disabled.query(Instant.now().minusSeconds(60), Instant.now())).isEmpty();
    }

    // ---------- 保留策略清理（DATA_RETENTION A3：AuditRetentionTarget） ----------

    @Test
    void 清理删除旧日期文件保留当天与新文件() throws Exception {
        Instant oldDay = Instant.parse("2026-07-01T10:00:00Z");
        Instant midDay = Instant.parse("2026-08-15T10:00:00Z");
        Instant recentDay = Instant.parse("2026-09-10T10:00:00Z");
        service.record(entry(oldDay, "run-old", 1));
        service.record(entry(midDay, "run-mid", 2));
        service.record(entry(recentDay, "run-recent", 3));
        Path auditDir = tempDir.resolve("logs").resolve("audit");

        int removed = service.cleanupBefore(Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(removed).isEqualTo(2);
        assertThat(auditDir.resolve("audit-2026-07-01.jsonl")).doesNotExist();
        assertThat(auditDir.resolve("audit-2026-08-15.jsonl")).doesNotExist();
        assertThat(auditDir.resolve("audit-2026-09-10.jsonl")).exists();
    }

    @Test
    void 清理边界当天文件保留() throws Exception {
        Instant day = Instant.parse("2026-09-01T10:00:00Z");
        service.record(entry(day, "run-day", 1));
        // 划线当天 09-01 → 文件日期不早于划线日期 → 保留
        int removed = service.cleanupBefore(Instant.parse("2026-09-01T23:00:00Z"));
        assertThat(removed).isZero();
        assertThat(tempDir.resolve("logs").resolve("audit").resolve("audit-2026-09-01.jsonl"))
                .exists();
    }

    @Test
    void 目录缺失时清理返回0() {
        AuditProperties properties = new AuditProperties();
        properties.setDirectory(tempDir.resolve("nope"));
        assertThat(new AuditLogService(properties).cleanupBefore(Instant.now())).isZero();
    }
}
