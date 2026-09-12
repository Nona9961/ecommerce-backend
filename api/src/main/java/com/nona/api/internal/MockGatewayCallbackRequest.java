package com.nona.api.internal;

import jakarta.validation.constraints.NotNull;

/**
 * mock 网关回调送达请求体（内部回调契约，服务端实现位于 server 模块）。
 * <p>
 * 字段与支付域端口载荷 {@code GatewayCallbackPayload} 一一对应（契约
 * 结构冻结于网关抽象阶段：回调端点 body 即该 JSON 结构）；语义分层——
 * <ul>
 *     <li><b>结构性校验</b>（本 record，JSR-380）：type / payNo / result /
 *         channelTxnNo / amountCents 五个字段存在性（{@code @NotNull}）；
 *         字段缺失 = 客户端结构错误 → 框架校验面 400；</li>
 *     <li><b>语义校验</b>（收敛渠道侧，单一校验源）：空白单号、金额非正、
 *         类型与字段不配套（REFUND 缺 refundNo / PAY 携 refundNo）一律由
 *         渠道回调校验拒绝（{@code payment.gateway_callback_invalid}）——
 *         控制器不重复实现校验，防止双源漂移（refundNo 因此不设 JSR 注解：
 *         PAY 时必空、REFUND 时必填属类型配套语义）。</li>
 * </ul>
 * 幂等（重复回调）、状态迁移与回调留痕由业务侧回调编排承载，本契约不
 * 涉及。
 *
 * @param type          回调类型（支付/退款）
 * @param payNo         业务支付单号（受理时回显原值）
 * @param refundNo      业务退款单号（type=REFUND 时必填，PAY 时必空）
 * @param result        渠道侧业务结果
 * @param channelTxnNo  渠道流水号（支付回调 = acquire 受理流水；退款回调
 *                       = refund 受理流水；必填）
 * @param amountCents   回调金额（分，渠道回传的实收/实退金额；业务侧按
 *                       此核对受理金额一致性，本契约只校验存在性，正数
 *                       语义由渠道校验承载）
 */
public record MockGatewayCallbackRequest(
        @NotNull CallbackType type,
        @NotNull String payNo,
        String refundNo,
        @NotNull GatewayResult result,
        @NotNull String channelTxnNo,
        @NotNull Long amountCents
) {
}