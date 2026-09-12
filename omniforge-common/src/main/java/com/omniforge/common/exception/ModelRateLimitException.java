package com.omniforge.common.exception;

/** 模型提供商限流（HTTP 429 等），调用方可据此退避重试或切换模型。 */
public class ModelRateLimitException extends OmniForgeException {

    public ModelRateLimitException(String message) {
        super(message);
    }

    public ModelRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
