package com.omniforge.app.headless;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Headless 模式配置（前缀 {@code omniforge.headless}）。 */
@ConfigurationProperties(prefix = "omniforge.headless")
public class HeadlessProperties {

    /** 健康检查/指标端口（默认 5119，需求 Phase 3） */
    private int port = 5119;

    /** 是否启用 /metrics（Prometheus 文本格式） */
    private boolean metricsEnabled = true;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }
}
