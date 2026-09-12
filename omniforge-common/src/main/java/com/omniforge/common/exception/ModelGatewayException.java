package com.omniforge.common.exception;

/** 模型网关异常：模型调用失败、别名未知等通用错误（单模型异常不影响其他模型，见需求 9.3）。 */
public class ModelGatewayException extends OmniForgeException {

    public ModelGatewayException(String message) {
        super(message);
    }

    public ModelGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
