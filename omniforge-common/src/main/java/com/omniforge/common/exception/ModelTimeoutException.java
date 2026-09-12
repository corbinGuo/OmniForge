package com.omniforge.common.exception;

/** 模型调用超时（需求 4.2 时间熔断：单轮超时自动终止）。 */
public class ModelTimeoutException extends OmniForgeException {

    public ModelTimeoutException(String message) {
        super(message);
    }

    public ModelTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
