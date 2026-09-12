package com.omniforge.core.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * 会话上下文管理（需求 4.2「上下文管理与状态持久化」中的上下文管理部分）：
 * 以会话为单位累积多轮对话历史，逼近预算时自动裁剪，长对话可持续且不超模型窗口。
 *
 * <p>设计要点：
 * <ul>
 *   <li>存储为进程内存态（重启丢失，由「清空上下文」语义兜底；落库随企业版会话管理）；</li>
 *   <li>user/assistant 轮对原子裁剪（{@link ContextTrimmer}）；</li>
 *   <li>会话容量上限 + 空闲 TTL 双路径淘汰（访问懒淘汰 + 定时清扫，防内存泄漏）；</li>
 *   <li>设置经 {@link ContextSettingsHolder} 热生效（enabled=false 时本接口返回空历史，
 *       调用方完全回退单条消息现状）。</li>
 * </ul>
 */
public interface ContextManager {

    /**
     * 追加一条会话历史。user/assistant 成对追加（回复完成后追加 assistant，
     * 失败的轮次不追加，避免破坏交替结构）。
     */
    void append(String sessionId, ContextRole role, String content);

    /**
     * 返回裁剪后的历史条目（供调用方自行转换消息类型）。
     *
     * @param sessionId          会话 ID（唯一键）
     * @param userText           本轮用户输入（永远保留，计入预算）
     * @param modelContextWindow 模型上下文窗口 token（null = 未知，仅按全局预算）
     */
    List<ContextEntry> trimmedHistory(String sessionId, String userText, Integer modelContextWindow);

    /**
     * 便捷方法：裁剪后历史直接映射为 Spring AI Message（system → SystemMessage，
     * user → UserMessage，assistant → AssistantMessage），供 Agent 引擎注入。
     */
    default List<Message> buildHistory(String sessionId, String userText, Integer modelContextWindow) {
        return trimmedHistory(sessionId, userText, modelContextWindow).stream()
                .map(entry -> switch (entry.role()) {
                    case SYSTEM -> (Message) new SystemMessage(entry.content());
                    case USER -> (Message) new UserMessage(entry.content());
                    case ASSISTANT -> (Message) new AssistantMessage(entry.content());
                })
                .toList();
    }

    /** 清空会话上下文（「清空上下文」按钮） */
    void clear(String sessionId);

    /** 会话统计（轮数/条目数/估算 token） */
    ContextStats stats(String sessionId);
}
