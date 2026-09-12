package com.omniforge.app.safety;

import com.omniforge.common.spi.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GUI 确认通道（HITL，A1）单测：无窗口/未就绪场景宁严勿松返回 false（TOOL_CONFIRMATION §四）。 */
class AgentSafetyGuiApprovalTest {

    @Test
    void 无GUI实例时确认返回false宁严勿松() {
        // 单测环境不启动 JavaFX → OmniForgeApplication.instance == null → 等同拒绝
        AgentSafetyGuiApproval approval = new AgentSafetyGuiApproval();
        ToolSpec spec = new ToolSpec("shell_executor", "执行命令",
                Map.of("type", "object"), true);
        assertThat(approval.approve(spec, Map.of("command", "whoami"))).isFalse();
    }
}
