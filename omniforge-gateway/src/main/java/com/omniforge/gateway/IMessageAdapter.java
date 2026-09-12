package com.omniforge.gateway;

import java.util.Map;

/**
 * IM 平台适配器 SPI（Phase 3 Step 2 定义接口，Step 3/4 各平台实现）。
 *
 * <p>实现约定：
 * <ul>
 *   <li>{@link #parse} 内含平台签名校验（钉钉/飞书 Webhook 签名、邮件凭证等），
 *       校验失败抛 {@link IllegalArgumentException}；</li>
 *   <li>实现必须无状态、线程安全（同一实例被并发回调）；</li>
 *   <li>异常隔离：单个平台适配器异常不得影响其他平台。</li>
 * </ul>
 */
public interface IMessageAdapter {

    /** 平台标识（与 ImInboundMessage.platform 一致，如 "dingtalk"） */
    String platform();

    /** 平台显示名（如 "钉钉"） */
    String displayName();

    /**
     * 将平台回调（HTTP 头 + 请求体）解析为统一入站消息。
     *
     * @param headers 请求头（含签名相关字段，各平台不同）
     * @param body    原始请求体（JSON 等）
     * @throws IllegalArgumentException 签名校验失败或载荷不合法时抛出
     */
    ImInboundMessage parse(Map<String, String> headers, String body);
}
