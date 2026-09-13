package com.omniforge.ui.collab;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** D7 方向 A：@ 提及解析（Q1/Q2-A）纯函数用例。 */
class CollabMentionParserTest {

    private static final List<String> MODELS = List.of("deepseek-chat", "qwen-plus", "gpt-4o-mini");

    @Test
    void 两个有效提及识别为协作并提取主题() {
        var r = CollabMentionParser.parse("@deepseek-chat @qwen-plus 讨论一下缓存设计", MODELS);
        assertThat(r.isCollab()).isTrue();
        assertThat(r.aliases()).containsExactly("deepseek-chat", "qwen-plus");
        assertThat(r.topic()).isEqualTo("讨论一下缓存设计");
    }

    @Test
    void 辩论关键词映射模板并剥离() {
        var r = CollabMentionParser.parse("@deepseek-chat @qwen-plus 用辩论模式看这个问题 辩论", MODELS);
        assertThat(r.template()).isEqualTo("正反辩论");
        assertThat(r.topic()).doesNotContain("辩论");
    }

    @Test
    void 三轮识别为轮次三且从主题剥离() {
        var r = CollabMentionParser.parse("@deepseek-chat @qwen-plus 评审这个方案 3轮", MODELS);
        assertThat(r.template()).isEqualTo("方案评审");
        assertThat(r.rounds()).isEqualTo(3);
        assertThat(r.topic()).doesNotContain("3轮").doesNotContain("评审");
    }

    @Test
    void 轮次钳制一到十() {
        assertThat(CollabMentionParser.parse("@a @b 99轮 x", List.of("a", "b")).rounds()).isEqualTo(10);
        assertThat(CollabMentionParser.parse("@a @b 0轮 x", List.of("a", "b")).rounds()).isEqualTo(1);
    }

    @Test
    void 单个提及识别为单聊() {
        var r = CollabMentionParser.parse("@deepseek-chat 你好", MODELS);
        assertThat(r.isSingle()).isTrue();
        assertThat(r.aliases()).containsExactly("deepseek-chat");
    }

    @Test
    void 无提及为普通对话() {
        var r = CollabMentionParser.parse("现在几点了", MODELS);
        assertThat(r.isCollab()).isFalse();
        assertThat(r.isSingle()).isFalse();
        assertThat(r.unknownAliases()).isEmpty();
    }

    @Test
    void 未知名列出提示且不发起() {
        var r = CollabMentionParser.parse("@deepseek @qwensp 讨论", MODELS);
        assertThat(r.isCollab()).isFalse();
        assertThat(r.unknownAliases()).containsExactly("deepseek", "qwensp");
    }

    @Test
    void 别名匹配忽略大小写并回填规范名() {
        var r = CollabMentionParser.parse("@DeepSeek-Chat @QWEN-PLUS 讨论", MODELS);
        assertThat(r.aliases()).containsExactly("deepseek-chat", "qwen-plus");
    }

    @Test
    void 去重相同提及只算一个() {
        var r = CollabMentionParser.parse("@deepseek-chat @deepseek-chat @qwen-plus 讨论", MODELS);
        assertThat(r.isCollab()).isTrue();
        assertThat(r.aliases()).hasSize(2);
    }

    @Test
    void 光标片段检测触发自动补全() {
        assertThat(CollabMentionParser.mentionFragment("你好 @deep")).isEqualTo("deep");
        assertThat(CollabMentionParser.mentionFragment("你好 @")).isEqualTo("");
        assertThat(CollabMentionParser.mentionFragment("你好 @deep 已发")).isNull();
        assertThat(CollabMentionParser.mentionFragment("普通文本")).isNull();
        assertThat(CollabMentionParser.mentionFragment(null)).isNull();
    }

    @Test
    void 候选过滤大小写不敏感包含匹配() {
        var all = CollabMentionParser.suggestModels("", MODELS);
        assertThat(all).hasSize(3);
        var hit = CollabMentionParser.suggestModels("DEEP", MODELS);
        assertThat(hit).containsExactly("deepseek-chat");
        assertThat(CollabMentionParser.suggestModels("zzz", MODELS)).isEmpty();
    }
}
