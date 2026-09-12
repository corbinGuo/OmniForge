/**
 * 邮件适配器（Phase 3 Step 3，Jakarta Mail + Angus）。
 *
 * <p>IMAP 轮询收件（默认 30s，仅 text/plain 正文；附件流式落盘
 * ~/.omniforge/temp/attachments/，默认上限 10MB，不加载进内存）；
 * SMTP 回复原邮件（Auto-Submitted: auto-generated + X-AI-Generated: true 合规头 + AI 标识脚注）；
 * 单封邮件失败不阻塞后续。</p>
 */
package com.omniforge.gateway.email;
