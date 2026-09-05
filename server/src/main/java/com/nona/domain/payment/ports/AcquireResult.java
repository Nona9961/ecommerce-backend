package com.nona.domain.payment.ports;

/**
 * 支付受理结果（acquire 返回）：渠道是否受理 + 渠道侧受理标识。
 * <p>
 * 受理 ≠ 支付结果：业务侧收到本结果只能推进「待支付」状态，最终结果
 * 经回调（{@link GatewayCallbackPayload}）异步到达。受理被拒绝
 * （accepted=false，如渠道风控/白名单）时无后续回调，业务侧应按受理
 * 失败处理（支付单关闭语义由消费方编排）。
 *
 * @param accepted       是否受理成功（false = 渠道拒绝受理，无后续回调）
 * @param payNo          业务支付单号（原样回显）
 * @param channelTxnNo   渠道流水号（受理即生成；成功回调携带同一流水号，
 *                       业务侧唯一约束防线依赖其唯一性）
 * @param cashierToken   收银台标识（前端跳转模拟收银页的参数；真实渠道
 *                       为支付链接/二维码/表单参数，由渠道实现自行承载）
 * @author nona9961
 */
public record AcquireResult(boolean accepted, String payNo, String channelTxnNo, String cashierToken) {
}