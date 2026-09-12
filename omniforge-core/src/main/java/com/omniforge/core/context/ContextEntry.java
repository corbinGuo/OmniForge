package com.omniforge.core.context;

/**
 * 会话上下文中的一条历史消息。
 *
 * @param role            消息角色（USER/ASSISTANT 成对；SYSTEM 为摘要条目）
 * @param content         消息内容
 * @param estimatedTokens 估算 token 数（{@link TokenEstimator}，用于裁剪决策）
 */
public record ContextEntry(ContextRole role, String content, int estimatedTokens) {

    public ContextEntry {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        content = content == null ? "" : content;
    }
}
