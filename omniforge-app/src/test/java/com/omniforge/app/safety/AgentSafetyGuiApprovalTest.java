package com.omniforge.app.safety;

import com.omniforge.common.spi.ToolSpec;
import com.omniforge.tools.ToolsSettings;
import com.omniforge.tools.ToolsSettingsHolder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GUI 确认通道（HITL，A1）单测：无窗口/未就绪场景宁严勿松返回 false（TOOL_CONFIRMATION §四）；U11 确认开关闸门。 */
class AgentSafetyGuiApprovalTest {

    private final ToolSpec spec = new ToolSpec("shell_executor", "执行命令",
            Map.of("type", "object"), true);

    @Test
    void 无GUI实例时确认返回false宁严勿松() {
        // 单测环境不启动 JavaFX → OmniForgeApplication.instance == null → 等同拒绝
        AgentSafetyGuiApproval approval = new AgentSafetyGuiApproval();
        assertThat(approval.approve(spec, Map.of("command", "whoami"))).isFalse();
    }

    @Test
    void 确认开关关闭时自动放行不弹窗() {
        // U11：confirmationRequired=false → 直接放行（不触达 UI 静态入口）
        ToolsSettingsHolder holder = new ToolsSettingsHolder(
                new ToolsSettings(false, true, java.util.List.of(), false));
        AgentSafetyGuiApproval approval = new AgentSafetyGuiApproval(holder);
        assertThat(approval.approve(spec, Map.of("command", "whoami"))).isTrue();
    }

    @Test
    void 确认开关开启时走弹窗通道() {
        // U11：confirmationRequired=true（默认）→ 无 GUI 实例 → 宁严勿松 false（证明未短路放行）
        ToolsSettingsHolder holder = new ToolsSettingsHolder(ToolsSettings.defaults());
        AgentSafetyGuiApproval approval = new AgentSafetyGuiApproval(holder);
        assertThat(approval.approve(spec, Map.of("command", "whoami"))).isFalse();
    }
}
