package com.omniforge.core.gateway;

/**
 * 可用模型信息（热重载后变化，供 UI 展示与路由决策）。
 *
 * @param alias              模型别名
 * @param providerName       提供方名称
 * @param modelId            提供方模型 ID
 * @param inputPricePer1m    输入定价（美元/百万 token）
 * @param outputPricePer1m   输出定价（美元/百万 token）
 * @param timeoutSeconds     调用超时（秒）
 * @param contextWindowTokens 上下文窗口 token（null = 未知，上下文裁剪仅按全局预算）
 */
public record ModelInfo(String alias, String providerName, String modelId,
                        double inputPricePer1m, double outputPricePer1m, int timeoutSeconds,
                        Integer contextWindowTokens) {

    /** 便捷构造：无上下文窗口配置（兼容既有调用方） */
    public ModelInfo(String alias, String providerName, String modelId,
                     double inputPricePer1m, double outputPricePer1m, int timeoutSeconds) {
        this(alias, providerName, modelId, inputPricePer1m, outputPricePer1m, timeoutSeconds, null);
    }
}
