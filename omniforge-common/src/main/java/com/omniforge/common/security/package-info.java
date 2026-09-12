/**
 * JCE 加密与敏感信息掩码（需求 9.2 安全底线：API Key 加密存储；严禁日志明文记录敏感信息）。
 *
 * <p>方案：{@link com.omniforge.common.security.CryptoUtil} 提供 AES-256-GCM 加解密；
 * {@link com.omniforge.common.security.SecretKeyStore} 管理主密钥与机密文件；
 * {@link com.omniforge.common.security.SensitiveMasker} 用于日志脱敏。
 * Windows DPAPI / ACL 收紧列入 Phase 4 加固。</p>
 */
package com.omniforge.common.security;
