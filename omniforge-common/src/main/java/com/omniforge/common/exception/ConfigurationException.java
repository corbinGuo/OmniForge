package com.omniforge.common.exception;

/** 配置异常：配置文件缺失、格式错误或引用无效（如 models.yml 引用了不存在的提供商）。 */
public class ConfigurationException extends OmniForgeException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
