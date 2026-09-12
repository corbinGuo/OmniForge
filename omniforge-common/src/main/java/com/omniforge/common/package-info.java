/**
 * OmniForge 公共基础库（com.omniforge.common，Apache 2.0）。
 *
 * <p>所有模块共享的地基设施，不依赖任何业务模块：
 * <ul>
 *   <li>{@code spi} —— 工具 SPI 契约（MCP / OFT 双轨制的基础抽象）</li>
 *   <li>{@code event} —— 进程内事件总线（虚拟线程分发）</li>
 *   <li>{@code exception} —— 统一异常体系</li>
 *   <li>{@code security} —— JCE 加密与敏感信息掩码（API Key 安全存储底线）</li>
 *   <li>{@code logging} —— MDC 日志追踪上下文</li>
 * </ul>
 */
package com.omniforge.common;
