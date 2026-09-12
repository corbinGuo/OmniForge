package com.omniforge.ui.enterprise;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 企业版文档产出（D5）Markdown 合成器单测：无消息 / 纯会话 / 会话+协作穿插 / 协作段截断。 */
class DocDraftBuilderTest {

    private static DocDraftBuilder.ChatLine msg(String role, String content, String time) {
        return new DocDraftBuilder.ChatLine(role, content, LocalDateTime.parse(time));
    }

    private static DocDraftBuilder.CollabRun collab(String id, String createdAt, String status,
                                                    String topic, String transcript, String conclusion,
                                                    String executionOutput, String verdict, String reason) {
        return new DocDraftBuilder.CollabRun(id, "discussion", topic, List.of("m1", "m2"),
                null, 1, status, conclusion, executionOutput, verdict, reason, transcript, createdAt);
    }

    @Test
    void 无消息无协作时仅元信息() {
        String md = DocDraftBuilder.build("空会话", "abc12345", List.of(), List.of());
        assertThat(md).contains("# 空会话");
        assertThat(md).contains("会话 ID：abc12345");
        assertThat(md).contains("对话 0 条 · 多模型协作 0 场");
        assertThat(md).doesNotContain("## ");
    }

    @Test
    void 纯会话消息按时间正序() {
        String md = DocDraftBuilder.build("会话", "sess1",
                List.of(msg("user", "问题", "2026-09-11T09:00"),
                        msg("assistant", "回答", "2026-09-11T09:01")),
                List.of());
        assertThat(md).contains("## 🧑 用户 · 2026-09-11 09:00");
        assertThat(md).contains("问题");
        assertThat(md).contains("## 🤖 助手 · 2026-09-11 09:01");
        assertThat(md).contains("回答");
        // 正序：用户段在助手段之前
        assertThat(md.indexOf("🧑 用户")).isLessThan(md.indexOf("🤖 助手"));
    }

    @Test
    void 会话与协作按时间正序穿插() {
        String md = DocDraftBuilder.build("会话", "sess1",
                List.of(msg("user", "早先消息", "2026-09-11T09:00"),
                        msg("assistant", "后续消息", "2026-09-11T09:30")),
                List.of(collab("run1", "2026-09-11 09:15", "done", "方案评审",
                        "第1行\n第2行\n第3行", "结论A", "执行B", "pass", "通过")));
        // 协作时间 09:15 介于 09:00 与 09:30 之间 → 穿插于两条消息之间
        assertThat(md.indexOf("🧑 用户")).isLessThan(md.indexOf("🧠 多模型协作"));
        assertThat(md.indexOf("🧠 多模型协作")).isLessThan(md.indexOf("🤖 助手"));
        // 协作段元信息
        assertThat(md).contains("## 🧠 多模型协作：方案评审");
        assertThat(md).contains("- 参与模型：m1 + m2");
        assertThat(md).contains("- 状态：已完成");
        // 讨论摘要：前 30 行全文
        assertThat(md).contains("第1行\n第2行\n第3行");
        assertThat(md).contains("### 结论");
        assertThat(md).contains("结论A");
        assertThat(md).contains("### 执行结果");
        assertThat(md).contains("执行B");
        assertThat(md).contains("### 验收评语");
        assertThat(md).contains("✅ 通过");
        assertThat(md).contains("通过");
    }

    @Test
    void 协作讨论超过30行截断为摘要() {
        StringBuilder transcript = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            transcript.append("行").append(i).append('\n');
        }
        String md = DocDraftBuilder.build("会话", "sess1", List.of(),
                List.of(collab("run1", "2026-09-11 09:15", "done", "主题",
                        transcript.toString(), "结论", "执行", "pass", "通过")));
        assertThat(md).contains("行1");
        assertThat(md).contains("行30");
        assertThat(md).doesNotContain("行31");
        assertThat(md).contains("（共 40 行，此处仅摘要）");
    }

    @Test
    void 协作无结论无执行时占位提示() {
        String md = DocDraftBuilder.build("会话", "sess1", List.of(),
                List.of(collab("run1", "2026-09-11 09:15", "concluding", "主题",
                        "讨论", null, null, null, null)));
        assertThat(md).contains("（尚未生成）");
        assertThat(md).contains("（尚未执行）");
        assertThat(md).contains("（尚未验收）");
    }
}
