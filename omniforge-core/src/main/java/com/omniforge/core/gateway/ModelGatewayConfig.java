package com.omniforge.core.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.omniforge.common.exception.ConfigurationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** models.yml 根配置对象（Jackson YAML 绑定）。 */
public class ModelGatewayConfig {

    @JsonProperty("default-model")
    private String defaultModel;

    private List<ProviderConfig> providers = new ArrayList<>();

    private List<ModelConfig> models = new ArrayList<>();

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public List<ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(List<ProviderConfig> providers) {
        this.providers = providers != null ? providers : new ArrayList<>();
    }

    public List<ModelConfig> getModels() {
        return models;
    }

    public void setModels(List<ModelConfig> models) {
        this.models = models != null ? models : new ArrayList<>();
    }

    /** 按别名查找模型配置（未找到返回 null） */
    public ModelConfig findModel(String alias) {
        return models.stream().filter(m -> alias.equals(m.getAlias())).findFirst().orElse(null);
    }

    /** 按名称查找提供商配置（未找到返回 null） */
    public ProviderConfig findProvider(String name) {
        return providers.stream().filter(p -> name.equals(p.getName())).findFirst().orElse(null);
    }

    /** 提供商索引：name → ProviderConfig */
    public Map<String, ProviderConfig> providerIndex() {
        return providers.stream().collect(Collectors.toMap(ProviderConfig::getName, Function.identity()));
    }

    /**
     * 校验配置完整性：provider 名称/类型非空、model 别名非空、
     * provider 引用存在、model-id 非空。校验失败抛 {@link ConfigurationException}。
     */
    public void validate() {
        Map<String, ProviderConfig> providerIndex = providerIndex();
        for (ProviderConfig provider : providers) {
            if (isBlank(provider.getName())) {
                throw new ConfigurationException("models.yml: provider.name 不能为空");
            }
            if (isBlank(provider.getType())) {
                throw new ConfigurationException("models.yml: provider[" + provider.getName() + "].type 不能为空");
            }
        }
        for (ModelConfig model : models) {
            if (isBlank(model.getAlias())) {
                throw new ConfigurationException("models.yml: model.alias 不能为空");
            }
            if (!providerIndex.containsKey(model.getProvider())) {
                throw new ConfigurationException(
                        "models.yml: model[" + model.getAlias() + "] 引用了不存在的 provider: " + model.getProvider());
            }
            if (isBlank(model.getModelId())) {
                throw new ConfigurationException("models.yml: model[" + model.getAlias() + "].model-id 不能为空");
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
