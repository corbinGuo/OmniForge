/**
 * 虚拟线程调度设施（需求 9.3：虚拟线程使用 ThreadFactory 统一管理）。
 *
 * <p>Java 21 虚拟线程轻量级、可大量创建且阻塞友好，
 * 适合大量 Agent 并发任务与 IM 异步任务队列场景。</p>
 */
package com.omniforge.core.scheduler;
