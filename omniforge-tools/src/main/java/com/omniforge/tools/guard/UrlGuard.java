package com.omniforge.tools.guard;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * URL 安全校验（需求 4.5：web_search 防 SSRF 攻击）。
 *
 * <p>约束：仅 http/https 协议、必须含主机名、禁止 userinfo、端口合法。
 * 说明：SSRF 的核心风险面在 SearXNG 服务端自身的抓取配置（建议其部署侧限制内网访问）；
 * 本工具侧保证：不执行 javascript:/file: 等伪协议、不携带内嵌凭据、查询参数安全编码。</p>
 */
public final class UrlGuard {

    private UrlGuard() {
    }

    /**
     * 校验 URL 并返回规范化 URI；非法时抛 {@link IllegalArgumentException}。
     */
    public static URI requireSafeHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL 格式非法: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("仅允许 http/https 协议: " + url);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机名: " + url);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URL 不允许携带用户信息: " + url);
        }
        int port = uri.getPort();
        if (port > 65535) {
            throw new IllegalArgumentException("URL 端口非法: " + url);
        }
        return uri;
    }
}
