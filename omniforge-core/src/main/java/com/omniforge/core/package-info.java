/**
 * OmniForge 核心引擎（com.omniforge.core，Apache 2.0）。
 *
 * <p>子包划分：
 * <ul>
 *   <li>{@code gateway} —— 模型网关（Spring AI Alibaba 统一适配、热重载、熔断、成本计量）</li>
 *   <li>{@code agent} —— Agent 引擎（Phase 1 后续）</li>
 *   <li>{@code debate} —— 多模型辩论引擎（Phase 2）</li>
 *   <li>{@code scheduler} —— 虚拟线程调度（统一 ThreadFactory）</li>
 *   <li>{@code persistence} —— JPA 持久化（Phase 1 后续）</li>
 * </ul>
 *
 * <p>本模块以 Spring autoconfiguration 库形式被桌面端（omniforge-app）
 * 与企业版服务端（omniforge-enterprise-server）复用。</p>
 */
package com.omniforge.core;
