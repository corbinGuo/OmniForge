/**
 * IM 消息网关（com.omniforge.gateway，Phase 3）。
 *
 * <p>统一消息模型 → 幂等去重（core 的 ImMessageDedupService，(message_id, platform) 复合唯一）
 * → 白名单权限校验 → 异步任务队列（虚拟线程）→ 处理器（Agent/辩论触发，Step 3+ 接入）。
 * 平台适配器实现 {@link com.omniforge.gateway.IMessageAdapter}：
 * 钉钉（纯 HTTP + Mac 签名，零 SDK）、飞书、邮件（Jakarta Mail）、企微。</p>
 */
package com.omniforge.gateway;
