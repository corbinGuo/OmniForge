/**
 * 统一异常体系。
 *
 * <p>所有业务异常继承 {@link com.omniforge.common.exception.OmniForgeException}，
 * 上层（UI / IM 网关）可捕获根类型统一兜底，具体子类型用于精确处理
 * （如 {@code ModelTimeoutException} 触发重试或熔断）。</p>
 */
package com.omniforge.common.exception;
