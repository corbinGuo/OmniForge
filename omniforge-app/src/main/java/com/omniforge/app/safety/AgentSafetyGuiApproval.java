package com.omniforge.app.safety;

import com.omniforge.common.spi.ToolApproval;
import com.omniforge.common.spi.ToolSpec;

import java.util.Map;

/**
 * GUI 确认通道（HITL，TOOL_CONFIRMATION §3.3）：
 * 委托 {@link com.omniforge.ui.OmniForgeApplication#confirmToolExecution} 静态入口弹确认框。
 * 仅 GUI 分支装配（AgentSafetyConfiguration 经 omniforge.headless 门控）；
 * Headless/企业上下文无此 Bean → 引擎自动放行 + WARN（Q3-A）。
 */
public final class AgentSafetyGuiApproval implements ToolApproval {

    @Override
    public boolean approve(ToolSpec spec, Map<String, Object> params) {
        return com.omniforge.ui.OmniForgeApplication.confirmToolExecution(spec, params);
    }
}
