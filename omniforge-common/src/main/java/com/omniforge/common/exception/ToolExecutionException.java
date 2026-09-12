package com.omniforge.common.exception;

/** 工具执行异常：工具 SPI 实现抛出未预期的运行时异常时，由引擎包装为本类型。 */
public class ToolExecutionException extends OmniForgeException {

    private final String toolName;

    public ToolExecutionException(String toolName, String message) {
        super(message);
        this.toolName = toolName;
    }

    public ToolExecutionException(String toolName, String message, Throwable cause) {
        super(message, cause);
        this.toolName = toolName;
    }

    /** 出错的工具名称（可为 null） */
    public String toolName() {
        return toolName;
    }
}
