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
 *     <li>{@link #autoComplete}——完成推进：买家确认收货与收货
 *         超时自动完成共用同一迁移（已发货 → 已完成 + 主单
 *         派生），由确认收货用例与超时引擎用例编排。</li>
 * </ul>
 * 契约演进：接口方法签名与语义已冻结（契约表），实现接线随
 * 各消费编排落位（调用方经应用层用例注入，不跨域直连仓储）；
 * 消费者（支付回调/发货/超时编排）按本签名装配，签名演进只增不改
 * ——退款面成员（{@link #beginRefund} / {@link #completeRefund} /
 * {@link #closeByTimeout}）随退款编排演进冻结：
 * <ul>
 *     <li>{@link #beginRefund}——买家退款申请推进（子单 已支付/已发货/
 *         已完成 → 退款中 + 主单派生），由退款申请编排消费；</li>
 *     <li>{@link #completeRefund}——退款成功推进（子单 退款中 → 已退款
 *         + 主单派生；发货超时关单路径子单已关闭幂等跳过——资金侧由
 *         退款单承载），由退款回调成功编排消费；</li>
 *     <li>{@link #closeByTimeout}——发货超时关单推进（子单 已支付 →
 *         已关闭 + 主单派生），由发货超时编排（系统入口 + 超时
 *         handler 复用面）消费。</li>
 * </ul>
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
     * 完成推进——买家确认收货与收货超时自动完成共用
     * （签名冻结；消费者：确认收货编排/收货超时调度——双入口同一迁移，
     * 触发源由调用方语义区分）。
     *
     * @param subOrderId 子订单 ID（已发货 → 已完成 + 主单派生）
     */
    void autoComplete(Long subOrderId);

    /**
     * 退款申请推进（签名冻结；消费者：退款申请编排——已支付/已发货/
     * 已完成子单 → 退款中 + 主单派生）。
     * <p>
     * 语义：按 subOrderId 装载子单（不存在 404 契约防御，编排层归属
     * 校验先行）——聚合迁移守卫内建（仅已支付/已发货/已完成可进入退款
     * 中，其余状态 {@code order.sub_status_illegal} 拒绝，守卫内建）；全部
     * 推进成功后按子单状态投影刷新主单整体状态（任一退款中 → 主单退款
     * 中），子单与主单同批保存。幂等/防重短路<b>不落本类</b>——由编排
     * 层先行判定（退款单防重 + 子单状态预检），本类保持「非法状态拒绝」
     * 的契约语义；消费者编排语义见 {@link #beginRefund} 文档。</p>
     *
     * @param subOrderId 子订单 ID（已支付/已发货/已完成 → 退款中 + 主单派生）
     */
    void beginRefund(Long subOrderId);

    /**
     * 退款成功推进（签名冻结；消费者：退款回调成功编排——子单
     * 退款中 → 已退款 + 主单派生）。
     * <p>
     * 语义：按 subOrderId 装载子单（不存在 404 契约防御）——子单状态
     * 分派：
     * <ul>
     *     <li>{@code REFUNDING} → 聚合迁移 {@code markRefunded}（仅退款
     *         中可迁移，其余 {@code order.sub_status_illegal} 拒绝）+ 主单
     *         按子单投影派生 + 同批保存；</li>
     *     <li>{@code CLOSED} → <b>幂等跳过</b>（发货超时关单退款路径：
     *         履约侧终态已定格，资金侧退款成功由退款单承载
     *         {@code SUCCEEDED}——领域模型明示 ship-timeout 子单终态为
     *         CLOSED 而非 REFUNDED，本方法不迁移不落库）；</li>
     *     <li>其余状态 → {@code order.sub_status_illegal}（防御拒绝——
     *         退款成功回调到达未退款流程的子单属数据异常）。</li>
     * </ul>
     *
     * @param subOrderId 子订单 ID（退款中 → 已退款 + 主单派生；已关闭幂等跳过）
     */
    void completeRefund(Long subOrderId);

    /**
     * 发货超时关单推进（签名冻结；消费者：发货超时编排——子单
     * 已支付 → 已关闭 + 主单派生；退款编排由调用方部署，资金侧状态由
     * 退款单承载）。
     * <p>
     * 语义：按 subOrderId 装载子单（不存在 404 契约防御）——聚合迁移
     * {@code closeByTimeout}（仅已支付未发货子单可超时关单，其余状态
     * {@code order.sub_status_illegal} 拒绝——支付超时走取消，重复关闭
     * 拒绝）+ 主单按子单投影刷新整体状态（全部已关闭 → 主单已关闭）+ 
     * 同批保存。幂等短路<b>不落本类</b>——由编排层先行判定（子单状态
     * 预检，超时重扫不重复推进），本类保持「非法状态拒绝」的契约语义。
     *
     * @param subOrderId 子订单 ID（已支付 → 已关闭 + 主单派生）
     */
    void closeByTimeout(Long subOrderId);
}