package com.nona.domain.payment.ports;

/**
 * 退款回调处理端口（payment 跨上下文契约，本阶段冻结）：REFUND 类型
 * 回调的业务侧处理入口（三层幂等防线在退款面的编排消费面）——
 * 渠道回调经 web 挂点 → {@link PaymentGateway#handleCallback} 标准化
 * 校验 → 本端口装载退款单并执行留痕与状态迁移。
 * <p>
 * 语义契约（实现接线 = 端口实现 + 退款回调编排，签名冻结，
 * 回调编排补订单/库存同事务）：
 * <ol>
 *     <li><b>装载</b>：按回调 refundNo 装载退款单，不存在 →
 *         {@code payment.refund_not_found}（404，不产生孤儿处理路径）；</li>
 *     <li><b>防线三留痕先行</b>：回调原文（标准化字段 + 收到时间）先落
 *         refund_callback_log（appendCallbackRecord），再判迁移——重复/
 *         失败/金额不符等被拒回调同样留痕，对账不依赖迁移成败；</li>
 *     <li><b>防线二状态迁移</b>：按回调结果 markSucceeded /
 *         markRefundFailed（守卫判定顺序见 RefundOrder 类 javadoc）——
 *         同号重复回调命中 {@code payment.refund_status_illegal}，本端口
 *         捕获后按「已处理应答」返回（重复回调只生效一次，不重放
 *         订单/库存编排）；异号冲突与金额不符原样透传（渠道事故告警
 *         日志由编排落位）；</li>
 *     <li><b>成功编排挂点</b>：首次迁移成功后，同事务推进
 *         OrderFacade.completeRefund（子单已退款 + 主单派生；发货超时
 *         关单路径子单 CLOSED 幂等跳过）与未发货库存回补
 *         （InventoryFacade.restore，幂等键 (order_id, sku_id, type)
 *         兜底）——编排接线随退款回调编排接线落地，本端口只承载处理
 *         入口签名。</li>
 * </ol>
 * 边界：本端口只消费 REFUND 类型回调；PAY 类型回调由支付回调流
 * 契约接续，不进入本端口（对称隔离）。
 *
 * @author nona9961
 */
public interface RefundCallbackPort {

    /**
     * 处理退款回调（成功/失败统一入口；幂等/留痕/迁移语义见接口 javadoc）。
     *
     * @param callback 渠道回调校验通过后的标准化事件（handleCallback
     *                 返回，必填；type 必须为 REFUND）
     */
    void handleRefundCallback(ValidatedCallback callback);
}