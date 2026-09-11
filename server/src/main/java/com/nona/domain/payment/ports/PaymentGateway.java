package com.nona.domain.payment.ports;

/**
 * 支付渠道网关端口（payment 域跨上下文契约）：支付/退款渠道的统一抽象，
 * 签名按真实渠道<b>异步回调模型</b>设计——受理与结果分离：acquire/refund
 * 只表达「受理」，最终结果经渠道回调异步到达（handleCallback 入口）；
 * query 提供渠道侧状态按需查询。未来接真实渠道只换实现（inf 层替换
 * 实现类），域与编排层零改动。
 * <p>
 * 契约边界（编排层职责不在本接口内）：
 * <ul>
 *     <li>幂等防线（重复回调/状态机守卫/回调留痕）由业务侧回调编排与
 *         支付单聚合承载，本接口只做渠道语义自身的参数防御与标准化；</li>
 *     <li>业务单号生成（payNo/refundNo 的单号规则）不在本接口内——调用
 *         方在创建支付单/退款单时生成并传入（本接口把 payNo 当不透明
 *         外部单号原样回显）；</li>
 *     <li>回调传输通道（HTTP 端点、签名算法）由渠道实现与 web 层承载，
 *         本接口只定义标准化载荷结构。</li>
 * </ul>
 * 租户语义：渠道受理无租户概念（渠道侧只有单号与金额），本契约无
 * 租户参数；业务侧归属校验由调用方承载。
 *
 * @author nona9961
 */
public interface PaymentGateway {

    /**
     * 发起支付受理（异步回调模型：受理成功不代表支付成功）。
     *
     * @param request 受理请求（支付单号 + 金额）
     * @return 受理结果（渠道流水号 + 收银台参数；结果经回调异步到达）
     * @throws com.nona.exceptions.BusinessException 参数非法（单号空白/金额非
     *                                               正）——渠道拒绝受理
     */
    AcquireResult acquire(AcquireRequest request);

    /**
     * 渠道回调入口：校验并标准化回调载荷（验签模拟/参数防御——回调
     * payload 缺字段、金额非正、类型与字段不配套一律拒绝）；校验通过
     * 返回业务侧可消费的标准化回调事件。
     * <p>
     * 幂等（重复回调）、状态迁移与回调留痕由业务侧回调编排承载——
     * 本方法对同一载荷重复调用返回相同结果（无副作用）。
     *
     * @param payload 渠道回调载荷（标准化结构）
     * @return 校验通过的标准化回调事件
     * @throws com.nona.exceptions.BusinessException 回调非法（缺字段/金额非正/
     *                                               类型字段不配套）
     */
    ValidatedCallback handleCallback(GatewayCallbackPayload payload);

    /**
     * 发起退款受理（与支付同构的异步回调模型：受理成功不代表退款成功）。
     *
     * @param request 退款受理请求（支付单号 + 退款单号 + 金额）
     * @return 退款受理结果（渠道退款流水号；结果经 REFUND 回调异步到达）
     * @throws com.nona.exceptions.BusinessException 参数非法（单号空白/金额非
     *                                               正）——渠道拒绝受理
     */
    RefundResult refund(RefundRequest request);

    /**
     * 渠道侧支付状态查询（对账/回调丢失兜底的按需查询；渠道侧视角状态，
     * 与业务支付单状态机两套口径）。
     *
     * @param payNo 业务支付单号
     * @return 渠道侧状态（WAIT_PAY/SUCCESS/FAIL）
     * @throws com.nona.exceptions.BusinessException 参数非法（空白单号）
     */
    GatewayPaymentStatus query(String payNo);
}