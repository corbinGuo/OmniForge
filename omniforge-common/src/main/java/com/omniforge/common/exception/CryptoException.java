package com.omniforge.common.exception;

/** 加密/解密异常：密钥不匹配、密文被篡改（GCM 认证失败）或密钥库文件读写失败。 */
public class CryptoException extends OmniForgeException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
