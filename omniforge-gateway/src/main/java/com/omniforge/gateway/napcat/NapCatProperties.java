package com.omniforge.gateway.napcat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * NapCat（QQ · OneBot 11）接入配置（前缀 {@code omniforge.im.napcat}）。
 * 技术预览：出站走 OneBot 11 HTTP API，入站走 HTTP POST 事件上报。
 */
@ConfigurationProperties(prefix = "omniforge.im.napcat")
public class NapCatProperties {

    /** 接入开关（默认关闭） */
    private boolean enabled = false;

    /** NapCat HTTP 服务地址（出站调用 send_msg） */
    private String napcatHost = "127.0.0.1";

    /** NapCat HTTP 服务端口 */
    private int napcatPort = 3000;

    /** OneBot 鉴权 token（与 NapCat 网络配置一致；未配置时事件接收器拒绝启动） */
    private String token;

    /** OmniForge 事件接收端口（NapCat 上报地址指向 http://host:eventPort/onebot/event） */
    private int eventPort = 5120;

    /** 事件接收绑定地址（默认仅回环；远程 NapCat 时改 0.0.0.0） */
    private String eventBind = "127.0.0.1";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNapcatHost() {
        return napcatHost;
    }

    public void setNapcatHost(String napcatHost) {
        this.napcatHost = napcatHost;
    }

    public int getNapcatPort() {
        return napcatPort;
    }

    public void setNapcatPort(int napcatPort) {
        this.napcatPort = napcatPort;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getEventPort() {
        return eventPort;
    }

    public void setEventPort(int eventPort) {
        this.eventPort = eventPort;
    }

    public String getEventBind() {
        return eventBind;
    }

    public void setEventBind(String eventBind) {
        this.eventBind = eventBind;
    }
}
