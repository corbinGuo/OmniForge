package com.omniforge.ui.collab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** D8：协作阶段消息结构化内容（落库/恢复）纯函数用例。 */
class CollabStageMessageTest {

    @Test
    void 陈述消息含别名轮次与全文() {
        String json = CollabStageMessage.statement("deepseek-chat", 2, "第二轮观点全文");
        var stage = CollabStageMessage.parse(json);
        assertThat(stage).isNotNull();
        assertThat(stage.kind()).isEqualTo(CollabStageMessage.KIND_STATEMENT);
        assertThat(stage.alias()).isEqualTo("deepseek-chat");
        assertThat(stage.round()).isEqualTo(2);
        assertThat(stage.text()).isEqualTo("第二轮观点全文");
    }

    @Test
    void 阶段消息无别名轮次为零() {
        var stage = CollabStageMessage.parse(CollabStageMessage.stage("结论全文"));
        assertThat(stage.kind()).isEqualTo(CollabStageMessage.KIND_STAGE);
        assertThat(stage.alias()).isNull();
        assertThat(stage.round()).isZero();
        assertThat(stage.text()).isEqualTo("结论全文");
    }

    @Test
    void 检查点解析() {
        var stage = CollabStageMessage.parse(CollabStageMessage.checkpoint("⏸ 协作已暂停"));
        assertThat(stage.kind()).isEqualTo(CollabStageMessage.KIND_CHECKPOINT);
    }

    @Test
    void 非JSON与空内容解析为null() {
        assertThat(CollabStageMessage.parse(null)).isNull();
        assertThat(CollabStageMessage.parse("")).isNull();
        assertThat(CollabStageMessage.parse("纯文本旧内容")).isNull();
        assertThat(CollabStageMessage.parse("[1,2]")).isNull();
    }

    @Test
    void 中文与换行往返无损() {
        String text = "多行\n中文内容 \"引号\" {花括号}";
        var stage = CollabStageMessage.parse(CollabStageMessage.statement("a", 1, text));
        assertThat(stage.text()).isEqualTo(text);
    }
}
