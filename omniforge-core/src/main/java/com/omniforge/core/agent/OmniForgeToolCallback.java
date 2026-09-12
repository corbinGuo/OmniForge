package com.omniforge.core.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniforge.common.spi.Tool;
import com.omniforge.common.spi.ToolApproval;
import com.omniforge.common.spi.ToolRequest;
import com.omniforge.common.spi.ToolResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * Tool SPI → Spring AI ToolCallback 适配器（MCP/OFT 双轨制与 Spring AI 工具调用的桥梁）。
 *
 * <p>安全约定：工具实现抛出的任何异常都被转换为 "ERROR: ..." 文本返回给模型，
 * 绝不中断 Agent 循环；参数解析失败同理。</p>
 *
 * <p>HITL 确认（TOOL_CONFIRMATION）：构造时注入 {@link ToolApproval} 通道与
 * 交互确认开关（身份 os:* 判定），执行前查 {@link Tool#requiresConfirmation(Map)}；
 * 用户拒绝 → 返回 {@code ERROR: 用户拒绝执行 <工具名>}（Q2-A，循环继续但不执行）。
 * 无通道或非交互身份 → 直接执行（Q3-A 放行兜底）。</p>
 */
public final class OmniForgeToolCallback implements ToolCallback {

    private final Tool tool;
    private final ObjectMapper objectMapper;
    /** 确认通道（可为 null：无通道自动放行，Q3-A） */
    private final ToolApproval approval;
    /** 是否启用交互确认（仅桌面 os:* 身份为 true，Q4-A） */
    private final boolean interactiveApproval;

    public OmniForgeToolCallback(Tool tool) {
        this(tool, new ObjectMapper());
    }

    OmniForgeToolCallback(Tool tool, ObjectMapper objectMapper) {
        this(tool, objectMapper, null, true);
    }

    /** 完整构造：确认通道 + 交互开关（ReactAgentLoop 两处回调创建点注入，设计 §3.2） */
    OmniForgeToolCallback(Tool tool, ObjectMapper objectMapper, ToolApproval approval,
                          boolean interactiveApproval) {
        this.tool = tool;
        this.objectMapper = objectMapper;
        this.approval = approval;
        this.interactiveApproval = interactiveApproval;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        try {
            String schemaJson = objectMapper.writeValueAsString(
                    ToolSchema.objectSchema(tool.spec().parametersSchema()));
            return ToolDefinition.builder()
                    .name(tool.spec().name())
                    .description(tool.spec().description())
                    .inputSchema(schemaJson)
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具参数 Schema 序列化失败: " + tool.spec().name(), e);
        }
    }

    @Override
    public String call(String toolInput) {
        try {
            Map<String, Object> parameters = parseArguments(toolInput);
            // HITL（Q2-A）：需确认 + 交互身份 + 有通道 → 征求用户；拒绝即返回拒绝文本
            if (interactiveApproval && approval != null && tool.requiresConfirmation(parameters)) {
                if (!approval.approve(tool.spec(), parameters)) {
                    return "ERROR: 用户拒绝执行 " + tool.spec().name();
                }
            }
            ToolResult result = tool.execute(new ToolRequest(parameters, null, Map.of()));
            return switch (result.status()) {
                case SUCCESS -> result.output();
                case FAILED, TIMEOUT -> "ERROR: " + result.error();
            };
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private Map<String, Object> parseArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(toolInput, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            // 非法 JSON 转为运行时异常，由 call() 统一转换为 ERROR 文本反馈模型
            throw new IllegalArgumentException("工具参数不是合法 JSON: " + toolInput, e);
        }
    }
}
