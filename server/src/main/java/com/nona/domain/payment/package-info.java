/**
 * 支付通用域（payment）：PaymentOrder/RefundOrder 聚合（含持久化）与
 * PaymentGateway 端口（mock 实现）；当期实现。
 * <p>
 * 端口契约（ports）：{@code PaymentGateway} 按真实渠道异步回调形态设计
 * （受理与结果分离），回调幂等防线归业务侧编排；聚合与单号生成在支付单
 * 阶段落地。
 */
package com.nona.domain.payment;
