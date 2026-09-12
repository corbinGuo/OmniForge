package com.omniforge.core.gateway.router;

/**
 * 复杂度信号打分器（Pro 智能路由）。每个信号返回 0~1 的复杂度分：
 * 0 = 简单问题，1 = 高复杂度问题。实现必须线程安全且不得抛出异常
 * （异常视为信号不可用，跳过该信号）。
 */
public interface RouteScorer {

    /** 信号名称（对应 routing.yml weights 中的键） */
    String name();

    /** 对用户输入打分（0~1） */
    double score(String userText, RoutingSettings settings);
}
