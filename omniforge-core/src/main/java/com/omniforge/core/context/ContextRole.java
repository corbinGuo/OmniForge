package com.omniforge.core.context;

/**
 * 会话上下文中的消息角色。
 *
 * <p>SYSTEM 用于摘要条目（被裁剪历史压缩后的【对话历史摘要】），
 * USER/ASSISTANT 成对构成一轮对话（裁剪时整对原子删除，保持消息交替）。</p>
 */
public enum ContextRole {

    SYSTEM,
    USER,
    ASSISTANT
}
