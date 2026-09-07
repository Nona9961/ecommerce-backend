package com.nona.domain.payment.ports;

/**
 * 支付回调处理端口（payment 跨上下文契约，本阶段冻结）：支付成功/失败
 * 回调的业务侧处理入口（TD-11 三层幂等防线的编排消费面）——渠道回调经
 * web 挂点 → {@link PaymentGateway#handleCallback} 标准化校验 → 本端口
 * 装载支付单并执行留痕与状态迁移。
 * <p>
 * 语义契约（实现接线 = 端口实现 + 回调编排，红阶段冻结签名，
 * 回调编排补订单/库存同事务）：
 * <ol>
 *     <li><b>装载</b>：按回调 payNo 装载支付单，不存在 → {@code payment.not_found}
 *         （404，不产生孤儿处理路径）；</li>
 *     <li><b>防线三留痕先行</b>：回调原文（标准化六字段 + 收到时间）先落
 *         payment_callback_log（appendCallbackRecord），再判迁移——重复/
 *         失败/金额不符等被拒回调同样留痕，对账不依赖迁移成败；</li>
 *     <li><b>防线二状态迁移</b>：按回调结果 markPaid / markFailed（守卫
 *         判定顺序见 PaymentOrder 类 javadoc）——同号重复回调命中
 *         {@code payment.status_illegal}，本端口捕获后按「已处理应答」
 *         返回（B8.2 重复回调只生效一次，不重放订单/库存编排）；异号
 *         冲突与金额不符原样透传（渠道事故告警日志由编排落位）；</li>
 *     <li><b>防线一唯一约束</b>：channel_txn_no 唯一约束为并发重复回调的
 *         物理兜底（DB 冲突由实现转换为已处理应答语义或告警，随 回调编排
 *         编排落位）；</li>
 *     <li><b>成功编排挂点</b>：首次迁移成功后，同事务推进
 *         OrderFacade.onPaid（子单状态 + 主单派生）与库存确认扣除
 *         （InventoryFacade.confirmDeduct）——编排接线随回调编排接线落地，
 *         本端口只承载处理入口签名。</li>
 * </ol>
 * 边界：本端口只消费 PAY 类型回调；REFUND 类型回调由退款流（退款流）
 * 契约接续，不进入本端口。
 *
 * @author nona9961
 */
public interface PaymentCallbackPort {

    /**
     * 处理支付回调（成功/失败统一入口；幂等/留痕/迁移语义见接口 javadoc）。
     *
     * @param callback 渠道回调校验通过后的标准化事件（handleCallback 返回，
     *                 必填；type 必须为 PAY）
     */
    void handlePayCallback(ValidatedCallback callback);
}