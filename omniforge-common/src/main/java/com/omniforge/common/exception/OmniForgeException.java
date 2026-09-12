package com.omniforge.common.exception;

/**
 * OmniForge 所有业务异常的根类型。
 * 上层可通过捕获本类型统一兜底；具体子类型用于精确处理。
 */
public class OmniForgeException extends RuntimeException {

    public OmniForgeException(String message) {
        super(message);
    }

    public OmniForgeException(String message, Throwable cause) {
        super(message, cause);
    }

    public OmniForgeException(Throwable cause) {
        super(cause);
    }
}
