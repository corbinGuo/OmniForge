package com.omniforge.core.gateway.router;

import java.util.Objects;

/**
 * 身份路由规则（Batch2 权限模型）。
 *
 * @param priority        优先级（小者先匹配）
 * @param name            规则名（展示用）
 * @param identityPattern 身份通配模式（glob：* / ?）；匹配对象为身份键：
 *                        GUI="os:用户名"，IM="im:平台:发送者"。如 "im:dingtalk:admin_*"、"os:*"、"*"。
 * @param strategy        该身份命中的路由策略
 */
public record RoutingRule(int priority, String name, String identityPattern,
                          RoutingStrategy strategy) {

    public RoutingRule {
        name = name == null ? "" : name;
        identityPattern = identityPattern == null ? "*" : identityPattern;
        strategy = Objects.requireNonNull(strategy, "strategy");
    }

    /** 身份键是否匹配（glob：* 任意串、? 单字符；其余按字面） */
    public boolean matches(String identityKey) {
        if (identityKey == null) {
            return "*".equals(identityPattern);
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < identityPattern.length(); i++) {
            char c = identityPattern.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' ->
                        regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return identityKey.matches(regex.toString());
    }
}
