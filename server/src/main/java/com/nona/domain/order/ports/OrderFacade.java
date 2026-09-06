package com.nona.domain.order.ports;

/**
 * 订单门面（order 跨上下文契约，签名随本阶段冻结——ACL 冻结面，
 * 供支付回调/发货/超时等编排消费）：
 * <ul>
 *     <li>{@link #onPaid}——整单支付成功推进（主单下全部子单待支付 →
 *         已支付 + 主单状态派生），由支付回调用例编排（同事务：payment
 *         → order → inventory 扣减）；</li>
 *     <li>{@link #cancel}——待支付取消/支付超时关单入口（子单待支付 →
 *         已取消 + 主单派生；库存回滚编排同事务），由取消/超时用例编排；</li>
 *     <li>{@link #markShipped}——子单发货推进（已支付 → 已发货，归属
 *         店铺校验收敛在聚合方法 + 运单引用定型），由商家发货用例
 *         编排（同事务：sub-order 推进 + 物流运单创建）；</li>
 *     <li>{@link #autoComplete}——收货超时自动完成（已发货 → 已完成 +
 *         主单派生），由超时引擎用例编排。</li>
 * </ul>
 * 契约冻结说明：本接口方法签名与语义在 WU-27 冻结（0.5 契约表），
 * 实现接线随各消费编排 WU 落位（调用方经应用层用例注入，不跨域直连
 * 仓储）；消费者（支付回调/发货/超时编排）按本签名装配，签名演进
 * 只增不改。
 *
 * @author nona9961
 */
public interface OrderFacade {

    /**
     * 整单支付成功推进（签名冻结；消费者：支付回调用例）。
     *
     * @param masterOrderId 主订单 ID（主单下全部子单同事务推进）
     */
    void onPaid(Long masterOrderId);

    /**
     * 待支付取消/支付超时关单（签名冻结；消费者：取消用例/支付超时
     * 调度）。
     *
     * @param masterOrderId 主订单 ID（主单下全部子单同事务取消）
     * @param reason        取消原因（可空——超时自动取消无原因）
     */
    void cancel(Long masterOrderId, String reason);

    /**
     * 子单发货推进（签名冻结；消费者：商家发货用例——同事务创建运单
     * 后调用；归属店铺校验在聚合方法）。
     *
     * @param subOrderId 子订单 ID
     * @param waybillId  运单 ID（物流域创建后的引用，必填）
     */
    void markShipped(Long subOrderId, Long waybillId);

    /**
     * 收货超时自动完成（签名冻结；消费者：收货超时调度）。
     *
     * @param subOrderId 子订单 ID（已发货 → 已完成 + 主单派生）
     */
    void autoComplete(Long subOrderId);
}