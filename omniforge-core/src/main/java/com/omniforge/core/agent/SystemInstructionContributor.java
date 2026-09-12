package com.omniforge.core.agent;

/**
 * Agent 系统提示增量贡献者（P1-3 插件市场：Agent Skills）。
 *
 * <p>由装配层实现（如 omniforge-app 的 SkillContributor，读取 <配置>/skills/ 的
 * 启用技能），{@link ReactAgentLoop} 在组装首轮消息时收集所有贡献者的指令文本，
 * 拼接在调用方 systemText 之后、基线规则（BASE_SYSTEM_PROMPT）之前。
 * 未装配任何贡献者（contributors 为空）时消息组装与既有行为完全一致。</p>
 */
public interface SystemInstructionContributor {

    /**
     * 返回要并入 Agent 系统提示的额外指令文本。
     *
     * <p>应为「按需使用的技能指令」形态（如"当任务与 XX 相关时遵循以下要求……"），
     * 随每次调用实时读取（启用/停用技能热生效）。无内容返回 null 或空白串。</p>
     */
    String extraSystemText();
}
