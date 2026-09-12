/**
 * 模型网关（需求 4.1）。
 *
 * <p>基于 Spring AI Alibaba 的 ChatModel 接口实现统一模型适配：
 * 支持 DashScope、OpenAI、DeepSeek、智谱AI 等多个 LLM 提供商；
 * 配置热重载（models.yml 变更无需重启）；调用超时熔断；
 * 单模型异常不影响其他模型；累计 Token 成本计量（成本熔断数据基础）。</p>
 *
 * <p>智能路由（Pro 版）通过 {@link com.omniforge.core.gateway.ModelRouter} SPI 注入，
 * 社区版默认实现为别名直路由。</p>
 */
package com.omniforge.core.gateway;
