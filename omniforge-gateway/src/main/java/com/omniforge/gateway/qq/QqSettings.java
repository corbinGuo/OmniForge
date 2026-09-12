package com.omniforge.gateway.qq;

/**
 * QQ 官方机器人接入配置（qq-im.yml，配置目录下；用户自行在 QQ 开放平台注册应用获得 AppID/AppSecret）。
 *
 * @param enabled        接入开关（默认关闭）
 * @param appId          开放平台机器人 AppID
 * @param appSecret      开放平台机器人 AppSecret（Webhook Ed25519 验签 seed + token 获取凭证）
 * @param environment    环境：production / sandbox（未上架机器人沙箱联调）
 * @param webhookEnabled Webhook 接收开关（公网回调场景；默认走 WebSocket 即可）
 * @param webhookPort    Webhook 监听端口（开放平台仅允许 80/443/8080/8443）
 * @param webhookPath    Webhook 回调路径（开放平台管理端填 https://域名:端口/该路径）
 * @param webhookBind    Webhook 监听地址（默认仅回环；经反向代理转发时保持回环）
 * @param apiBaseUrl     API 基础地址（空=按环境推导；沙箱地址以平台控制台为准，可手改）
 */
public record QqSettings(boolean enabled, String appId, String appSecret, String environment,
                         boolean webhookEnabled, int webhookPort, String webhookPath, String webhookBind,
                         String apiBaseUrl) {

    /** 默认值（文件缺失时） */
    public static QqSettings defaults() {
        return new QqSettings(false, "", "", "production",
                false, 8080, "/webhook/qq", "127.0.0.1", "");
    }

    /** 归一化：环境白名单、端口兜底、路径兜底、base-url 推导 */
    public QqSettings normalize() {
        String env = "sandbox".equalsIgnoreCase(environment == null ? "" : environment) ? "sandbox" : "production";
        String base = (apiBaseUrl == null || apiBaseUrl.isBlank())
                ? ("sandbox".equals(env) ? "https://sandbox.api.sgroup.qq.com" : "https://api.bot.qq.com")
                : apiBaseUrl.strip();
        return new QqSettings(
                enabled,
                appId == null ? "" : appId.strip(),
                appSecret == null ? "" : appSecret,
                env,
                webhookEnabled,
                webhookPort > 0 ? webhookPort : 8080,
                (webhookPath == null || webhookPath.isBlank()) ? "/webhook/qq" : webhookPath.strip(),
                (webhookBind == null || webhookBind.isBlank()) ? "127.0.0.1" : webhookBind.strip(),
                base);
    }

    /** 凭证是否齐全（AppID 与 AppSecret 均配置） */
    public boolean hasCredentials() {
        QqSettings normalized = normalize();
        return !normalized.appId().isBlank() && !normalized.appSecret().isBlank();
    }
}
