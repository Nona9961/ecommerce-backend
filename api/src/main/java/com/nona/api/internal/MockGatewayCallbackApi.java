package com.nona.api.internal;

import com.nona.api.HttpResponse;

/**
 * mock 网关回调送达契约：支付/退款回调查看送达入口（内部端点，无身份
 * 校验语义——网关/系统触发，mock/演示专用；接真实渠道时随安全链放行
 * 项一并移除）。
 * <p>
 * 服务端实现位于 server 模块 {@code com.nona.web}（与 AuthController 平
 * 级）；路径 {@code POST /internal/mock-gateway/callback}，请求体 =
 * {@link MockGatewayCallbackRequest}（字段对齐支付域端口载荷
 * GatewayCallbackPayload）。
 * <p>
 * 处理语义（整链路）：请求体结构校验（JSR-380）→ 渠道回调校验
 * （MockPaymentGateway.handleCallback：缺字段/金额非正/类型与字段不配套
 * → {@code payment.gateway_callback_invalid}）→ 按 type 分发：PAY → 支付
 * 回调编排、REFUND → 退款回调编排（幂等三层防线/状态迁移/留痕归编排）；
 * 业务异常由全局 ExceptionAdviser 统一映射，本契约不承载错误应答。
 *
 * @author nona9961
 */
public interface MockGatewayCallbackApi {

    /**
     * 送达 mock 网关回调：校验并分发到支付/退款回调编排。
     *
     * @param request 回调请求体（type/payNo/refundNo/result/channelTxnNo/
     *                amountCents，字段对齐渠道载荷）
     * @return 成功响应（无数据）；校验/编排失败由全局异常处理返回业务码
     */
    HttpResponse<Void> handleCallback(MockGatewayCallbackRequest request);
}