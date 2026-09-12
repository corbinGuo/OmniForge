package com.omniforge.core.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 审计日志服务（C-tier 批次 4-1）：Agent 调用链结构化 JSON 存储与时间范围查询。
 *
 * <p>存储格式：{@code <目录>/audit-YYYY-MM-DD.jsonl}，每行一条 {@link AuditEntry} JSON。
 * 追加写；查询按时间范围筛选日期文件再逐行过滤。写入失败仅告警，
 * 绝不影响业务调用链（审计是旁路）。</p>
 */
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ObjectMapper jsonMapper = new ObjectMapper().findAndRegisterModules();
    private final Path directory;
    private final boolean enabled;

    public AuditLogService(AuditProperties properties) {
        this.directory = properties.getDirectory();
        this.enabled = properties.isEnabled();
    }

    /** 追加一条审计记录（按时间戳落到对应日期文件） */
    public void record(AuditEntry entry) {
        if (!enabled || entry == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
            String day = FILE_DATE.format(entry.timestamp().atZone(ZoneOffset.UTC).toLocalDate());
            Path file = directory.resolve("audit-" + day + ".jsonl");
            Files.writeString(file, jsonMapper.writeValueAsString(entry) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("审计记录写入失败（已跳过）：{}", e.getMessage());
        }
    }

    /**
     * 删除文件名日期早于划线的审计 JSONL 文件（保留策略 AuditRetentionTarget）。
     *
     * @param before 划线时刻（文件日期 < 划线日期则删除；当天文件保留）
     * @return 删除的文件数
     */
    public int cleanupBefore(Instant before) {
        if (!enabled || !Files.isDirectory(directory)) {
            return 0;
        }
        LocalDate beforeDay = before.atZone(ZoneOffset.UTC).toLocalDate();
        int removed = 0;
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(p -> p.getFileName().toString()
                            .matches("audit-\\d{4}-\\d{2}-\\d{2}\\.jsonl"))
                    .toList()) {
                String name = file.getFileName().toString();
                LocalDate day = LocalDate.parse(name.substring("audit-".length(),
                        "audit-".length() + 10), FILE_DATE);
                if (day.isBefore(beforeDay) && Files.deleteIfExists(file)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("审计清理失败（已跳过）：{}", e.getMessage());
        }
        return removed;
    }

    /**
     * 按时间范围查询审计记录（含边界，按时间升序）。
     * 损坏行跳过；目录缺失返回空列表。
     */
    public List<AuditEntry> query(Instant from, Instant to) {
        if (!enabled || !Files.isDirectory(directory)) {
            return List.of();
        }
        LocalDate fromDay = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toDay = to.atZone(ZoneOffset.UTC).toLocalDate();
        List<AuditEntry> entries = new ArrayList<>();
        for (LocalDate day = fromDay; !day.isAfter(toDay); day = day.plusDays(1)) {
            Path file = directory.resolve("audit-" + FILE_DATE.format(day) + ".jsonl");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                lines.forEach(line -> {
                    try {
                        AuditEntry entry = jsonMapper.readValue(line, AuditEntry.class);
                        if (!entry.timestamp().isBefore(from) && !entry.timestamp().isAfter(to)) {
                            entries.add(entry);
                        }
                    } catch (IOException | IllegalArgumentException e) {
                        log.debug("审计行解析失败（已跳过）：{}", e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("审计文件读取失败：{} —— {}", file.getFileName(), e.getMessage());
            }
        }
        entries.sort(Comparator.comparing(AuditEntry::timestamp));
        return entries;
    }
}
