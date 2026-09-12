package com.omniforge.app.safety;

import com.omniforge.common.spi.ToolApproval;
import com.omniforge.common.spi.ToolSpec;
import com.omniforge.tools.ToolsSettingsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * GUI 人工确认通道（HITL，A1）：委托主窗口弹确认框（60s 超时计拒绝）。
 *
 * <p>U11：配置中心「危险操作人工确认」关闭（tools.yml {@code confirmationRequired=false}）
 * 时自动放行并记录 INFO 日志——用户明示关闭即视为对危险操作的持续授权；
 * 开启（默认）则保持弹窗行为。Headless/IM 无本通道，仍走 Q3-A 无通道放行。</p>
 */
public final class AgentSafetyGuiApproval implements ToolApproval {

    private static final Logger log = LoggerFactory.getLogger(AgentSafetyGuiApproval.class);

    private final ToolsSettingsHolder settingsHolder;

    public AgentSafetyGuiApproval() {
        this(null);
    }

    /** holder 为 null 时保持旧行为（总是弹窗/无窗口宁严勿松） */
    public AgentSafetyGuiApproval(ToolsSettingsHolder settingsHolder) {
        this.settingsHolder = settingsHolder;
    }

    @Override
    public boolean approve(ToolSpec spec, Map<String, Object> params) {
        if (settingsHolder != null) {
            Boolean required = settingsHolder.current() == null
                    ? Boolean.TRUE : settingsHolder.current().confirmationRequired();
            if (!required) {
                log.info("危险操作人工确认已关闭，自动放行：{}", spec.name());
                return true;
            }
        }
        return com.omniforge.ui.OmniForgeApplication.confirmToolExecution(spec, params);
    }
}
