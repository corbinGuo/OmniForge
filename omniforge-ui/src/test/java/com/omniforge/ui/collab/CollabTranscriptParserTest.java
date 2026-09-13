package com.omniforge.ui.collab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** D7 方向 B：讨论记录解析（Q3/Q6）纯函数用例。 */
class CollabTranscriptParserTest {

    private static final String STRUCTURED = """
            {"rounds":[
              {"round":1,"texts":{"a":"第一轮A的观点全文","b":"第一轮B的观点全文"}},
              {"round":2,"texts":{"a":"第二轮A的总结","b":"第二轮B的总结"}}],
             "meta":{"mode":"debate","caption":"裁判判定获胜方","winner":"a","verdicts":[]}}
            """;

    @Test
    void 结构化JSON按轮次平铺且不截断() {
        var r = CollabTranscriptParser.parse(STRUCTURED, null);
        assertThat(r.blocks()).hasSize(2);
        assertThat(r.blocks().get(0).round()).isEqualTo(1);
        assertThat(r.blocks().get(0).statements()).hasSize(2);
        assertThat(r.blocks().get(1).statements().stream()
                .filter(s -> s.alias().equals("a")).findFirst().orElseThrow().text())
                .isEqualTo("第二轮A的总结");
        assertThat(r.caption()).isEqualTo("裁判判定获胜方");
        assertThat(r.winner()).isEqualTo("a");
        assertThat(r.singleFallback()).isNull();
    }

    @Test
    void 旧文本回退解析菱形段() {
        String legacy = "模式：debate｜结束：达到最大轮次\n"
                + "◆ deepseek-chat：我认为应该用本地缓存。\n"
                + "◆ qwen-plus：我反对，理由如下……";
        var r = CollabTranscriptParser.parse("{}", legacy);
        assertThat(r.blocks()).hasSize(1);
        assertThat(r.blocks().get(0).statements()).extracting(
                com.omniforge.ui.collab.CollabTranscriptParser.Statement::alias)
                .containsExactly("deepseek-chat", "qwen-plus");
        assertThat(r.caption()).contains("达到最大轮次");
    }

    @Test
    void 两者皆无时回退单全文块() {
        var r = CollabTranscriptParser.parse(null, "纯文本讨论记录全文");
        assertThat(r.blocks()).isEmpty();
        assertThat(r.singleFallback()).isEqualTo("纯文本讨论记录全文");
        assertThat(r.isEmpty()).isFalse();
    }

    @Test
    void 空数据与空文本为空结果() {
        assertThat(CollabTranscriptParser.parse(null, null).isEmpty()).isTrue();
        assertThat(CollabTranscriptParser.parse("{}", "").isEmpty()).isTrue();
        assertThat(CollabTranscriptParser.parse("not-json", null).isEmpty()).isTrue();
    }

    @Test
    void 结构化字段缺失回退旧文本() {
        var r = CollabTranscriptParser.parse("{\"rounds\":[]}", "◆ a：旧内容");
        assertThat(r.blocks()).hasSize(1);
        assertThat(r.blocks().get(0).statements().get(0).text()).isEqualTo("旧内容");
    }
}
