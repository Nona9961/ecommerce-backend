package com.nona.domain.payment.entity;

import java.time.Instant;
import java.util.List;

/**
 * 支付单聚合根（payment_order 主表行，买家维度 global，本阶段冻结）：
 * 一次支付请求的资金侧事实——支付单号/关联主单/金额/渠道/状态机/渠道
 * 流水号/超时截止 + 回调留痕集合（TD-11 三层幂等防线的领域承载）。
 * <p>
 * 持久化形态（红阶段契约声明）：payment_order 主表（global，独立
 * Snowflake 主键；pay_no 唯一；order_id 唯一——支付单与主单一对一，
 * 防重复发起；channel_txn_no 唯一——TD-11 防线一「重复回调插入即失败」，
 * 并发窗口的物理兜底；未回调为 NULL 时唯一约束允许多行，NULL 表达
 * 「尚未发生回调」的时间点语义而非默认值；(status, timeout_at) 复合
 * 索引——超时引擎扫描面）。回调留痕为从表 payment_callback_log，以
 * rootId（payment_order 主键）归属本聚合，由聚合仓储的从表机制
 * （getOther 装载/变更集驱动落库，Cart/CartItem 判例同构）加载与追加，
 * 无独立仓储。变更落库适配 DifferRepository：读 → track 快照 → save →
 * 变更集驱动落库。
 * <p>
 * 支付单与 订单状态机衔接：支付单承载资金侧状态（待支付→已支付/
 * 失败/关闭），订单承载履约侧状态（子单待支付→已支付…，主单派生）——
 * 两侧在回调编排同事务推进（payment.markPaid → order.onPaid → 库存
 * 确认扣除，回调编排接线）；支付单仅以 orderId 引用主单，不加载订单
 * 对象（跨聚合引用 ID 协作）。
 * <p>
 * 关键不变量（全部收敛在本聚合方法内，包外无字段变更路径——除
 * status/channelTxnNo 两个可变位经迁移方法变更外，字段全 final）：
 * <ol>
 *     <li><b>状态机单向（TD-11 防线二）</b>：仅 PENDING_PAYMENT 可出
 *         （→ PAID/FAILED/CLOSED），FAILED → CLOSED 收口，CLOSED 幂等，
 *         PAID 终态——非法迁移拒绝并告警（
 *         {@code payment.status_illegal}）；</li>
 *     <li><b>金额一致性</b>：回调金额必须 = 支付单金额（= 主单实付，
 *         创建时固化防中途改价）——不符拒绝（{@code payment.amount_mismatch}），
 *         不做半额/超额入账；</li>
 *     <li><b>渠道流水号占用一致（TD-11 防线一领域位）</b>：流水号已占用
 *         且异号即渠道事故，拒绝（{@code payment.callback_duplicate}，
 *         409）；主表唯一约束为并发窗口兜底；</li>
 *     <li><b>回调原文每笔留痕（TD-11 防线三）</b>：回调记录 append-only，
 *         先留痕后判迁移（重复/失败回调同样留痕可查），对账不依赖迁移
 *         成败；</li>
 *     <li><b>支付单与主单一对一</b>：order_id 唯一约束 + 发起支付编排列
 *         「复用/拒绝」判定守护（一期不支持分次支付）。</li>
 * </ol>
 * <p>
 * 迁移守卫判定顺序（markPaid 与 markFailed 同构，红阶段钉死契约）：
 * ① 流水号已占用且异号 → {@code payment.callback_duplicate}（渠道事故
 * 优先诊断）；② 状态非待支付 → {@code payment.status_illegal}（同号重复
 * 回调幂等命中同此码，编排捕获按已处理应答）；③ 金额不符 →
 * {@code payment.amount_mismatch}；④ 迁移 + 流水落位。
 * <p>
 * 创建必须经由 {@link com.nona.domain.payment.factory.PaymentOrderFactory}
 * （ID/payNo 生成收敛工厂一处）；装载（仓储重建：留痕集合按
 * payment_order_id 反查）走装载构造器。两构造器红阶段仅字段定型，
 * 形态守卫（必填/非空/非负）由绿阶段按 javadoc 契约实现。
 *
 * @author nona9961
 */
public class PaymentOrder {

    /**
     * 支付单主键（Snowflake，payment_order 主键，聚合根标识）
     */
    private final Long id;

    /**
     * 支付单号（TD-13：PAY + 日期 + snowflake 后段，pay_no 唯一）
     */
    private final String payNo;

    /**
     * 关联主单 ID（引用 ID 协作，order_id 唯一约束——一对一）
     */
    private final Long orderId;

    /**
     * 支付金额（分，= 主单实付，创建时固化）
     */
    private final long amount;

    /**
     * 支付渠道（一期唯一实现 MOCK，字段保留真实渠道扩展位）
     */
    private final String channel;

    /**
     * 支付超时截止时间（B8.3：待支付 30 分钟自动关单；创建时 =
     * 创建时刻 + 超时时长，(status, timeout_at) 复合索引扫描面）
     */
    private final Instant timeoutAt;

    /**
     * 回调留痕集合（append-only；创建恒为空集合——非空设计，无 null 语义）
     */
    private final List<PaymentCallbackRecord> callbacks;

    /**
     * 支付单状态（唯一可变位之一：仅经迁移方法变更）
     */
    private PaymentOrderStatus status;

    /**
     * 渠道流水号（唯一可变位之二：回调迁移时落位；NULL = 尚未发生回调
     * 的时间点语义，token 唯一约束允许多行 NULL——非「默认值」替换）
     */
    private String channelTxnNo;

    /**
     * 创建构造器（仅工厂路径）：待支付定型 + 空留痕集合 + 未回调流水。
     *
     * @param id        支付单主键（Snowflake）
     * @param payNo     支付单号（TD-13 规则，必填非空）
     * @param orderId   关联主单 ID（必填，一对一锚点）
     * @param amount    支付金额（分，= 主单实付）
     * @param channel   支付渠道（必填非空）
     * @param timeoutAt 支付超时截止时间（必填）
     */
    public PaymentOrder(Long id, String payNo, Long orderId, long amount,
                        String channel, Instant timeoutAt) {
        this.id = id;
        this.payNo = payNo;
        this.orderId = orderId;
        this.amount = amount;
        this.channel = channel;
        this.timeoutAt = timeoutAt;
        this.callbacks = List.of();
        this.status = PaymentOrderStatus.PENDING_PAYMENT;
        this.channelTxnNo = null;
    }

    /**
     * 装载构造器（仅仓储重建/转换器装载调用）：以持久化状态恢复聚合
     * （状态/流水/留痕集合为持久化值；留痕集合按 payment_order_id
     * 反查装载）。形态守卫（必填/非空/金额非负/集合组装防御）由绿阶段
     * 按 javadoc 契约实现；装载不执行写路径校验。
     *
     * @param id           支付单主键
     * @param payNo        支付单号
     * @param orderId      关联主单 ID
     * @param amount       支付金额（分）
     * @param channel      支付渠道
     * @param timeoutAt    支付超时截止时间
     * @param status       持久化状态
     * @param channelTxnNo 持久化渠道流水号（NULL = 未回调）
     * @param callbacks    持久化留痕集合（必填，可为空集合）
     */
    public PaymentOrder(Long id, String payNo, Long orderId, long amount,
                        String channel, Instant timeoutAt, PaymentOrderStatus status,
                        String channelTxnNo, List<PaymentCallbackRecord> callbacks) {
        this.id = id;
        this.payNo = payNo;
        this.orderId = orderId;
        this.amount = amount;
        this.channel = channel;
        this.timeoutAt = timeoutAt;
        this.status = status;
        this.channelTxnNo = channelTxnNo;
        this.callbacks = callbacks;
    }

    /**
     * 支付成功回调迁移（PENDING_PAYMENT → PAID）：
     * <p>
     * TD-11 防线二状态守卫 + 金额一致性与流水号占用守卫（判定顺序见类
     * javadoc）：① 异号冲突 409；② 非待支付拒绝（同号重复回调幂等命中
     * 同此码，编排捕获后按已处理应答——B8.2 重复回调只生效一次，不重放
     * 订单/库存编排）；③ 金额不符拒绝；④ 迁移 + 渠道流水落位（唯一约束
     * 并发兜底）。成功回调的订单/库存推进（order.onPaid / 库存确认扣除）
     * 由回调编排同事务接线（回调编排），本方法只收敛资金侧迁移。
     * <p>
     * 调用时序契约：编排先 {@link #appendCallbackRecord} 留痕（防线三，
     * 对账不依赖迁移成败），再调用本方法。
     *
     * @param channelTxnNo      渠道流水号（必填非空）
     * @param callbackAmountCents 回调金额（分，必须 = 支付单金额）
     */
    public void markPaid(String channelTxnNo, long callbackAmountCents) {
        throw new UnsupportedOperationException("红阶段契约：markPaid 实现留绿阶段（PaymentOrder 状态迁移）");
    }

    /**
     * 支付失败回调迁移（PENDING_PAYMENT → FAILED）：
     * <p>
     * 与 {@link #markPaid} 同构保证（成功/失败回调均落位渠道流水，唯一
     * 约束同时防并发双成功与双失败）。失败后支付单置 FAILED——订单侧
     * 停留待支付等待超时关单（B8.3），失败单可经超时编排
     * {@link #close()} 显式收口为 CLOSED。
     *
     * @param channelTxnNo      渠道流水号（必填非空）
     * @param callbackAmountCents 回调金额（分，必须 = 支付单金额）
     */
    public void markFailed(String channelTxnNo, long callbackAmountCents) {
        throw new UnsupportedOperationException("红阶段契约：markFailed 实现留绿阶段（PaymentOrder 状态迁移）");
    }

    /**
     * 关单收口（PENDING_PAYMENT/FAILED → CLOSED）：
     * <p>
     * 消费方 = 主动取消与支付超时编排（同事务：order.cancel → 库存回滚 →
     * payment.closePay）。语义钉死：已关闭重复关单<b>幂等成功</b>（超时
     * 调度与主动取消并发重放的常态路径，不报错不告警）；已支付<b>拒绝</b>
     * （资金已锁定，关单走退款流 退款流）；不存在由端口层按
     * {@code payment.not_found} 呈现。
     */
    public void close() {
        throw new UnsupportedOperationException("红阶段契约：close 实现留绿阶段（PaymentOrder 状态迁移）");
    }

    /**
     * 追加回调留痕记录（防线三，append-only）：
     * <p>
     * 时序契约：回调编排在状态迁移<b>之前</b>追加（先留痕后判迁移）——
     * 重复/失败/金额不符等被拒绝的回调同样留痕，排查与对账不依赖迁移
     * 成败。后续回调追加到集合尾部，记录不可变（无移除/修改路径）。
     *
     * @param record 回调留痕记录（必填，形态守卫由记录构造路径承载）
     */
    public void appendCallbackRecord(PaymentCallbackRecord record) {
        throw new UnsupportedOperationException("红阶段契约：appendCallbackRecord 实现留绿阶段（PaymentOrder 留痕追加）");
    }

    /**
     * 支付单主键。
     *
     * @return 主键
     */
    public Long getId() {
        throw new UnsupportedOperationException("红阶段契约：getId 实现留绿阶段（PaymentOrder）");
    }

    /**
     * 支付单号。
     *
     * @return 支付单号
     */
    public String getPayNo() {
        throw new UnsupportedOperationException("红阶段契约：getPayNo 实现留绿阶段（PaymentOrder）");
    }

    /**
     * 关联主单 ID。
     *
     * @return 主单 ID
     */
    public Long getOrderId() {
        throw new UnsupportedOperationException("红阶段契约：getOrderId 实现留绿阶段（PaymentOrder）");
    }

    /**
     * 支付金额（分，= 主单实付）。
     *
     * @return 金额
     */
    public long getAmount() {
        throw new UnsupportedOperationException("红阶段契约：getAmount 实现留绿阶段（PaymentOrder）");
    }

    /**
     * 支付渠道。
     *
     * @return 渠道
     */
    public String getChannel() {
        throw new UnsupportedOperationException("红阶段契约：getChannel 实现留绿阶段（PaymentOrder）");
    }

    /**
     * 支付超时截止时间。
     *
     * @return 截止时间
     */
    public Instant getTimeoutAt() {
        throw new UnsupportedOperationException("红阶段契约：getTimeoutAt 实现留绿阶段（PaymentOrder）");
    }

    /**
     * 支付单状态。
     *
     * @return 状态
     */
    public PaymentOrderStatus getStatus() {
        throw new UnsupportedOperationException("红阶段契约：getStatus 实现留绿阶段（PaymentOrder）");
    }

    /**
     * 渠道流水号（NULL = 尚未发生回调）。
     *
     * @return 流水号，或 null
     */
    public String getChannelTxnNo() {
        throw new UnsupportedOperationException("红阶段契约：getChannelTxnNo 实现留绿阶段（PaymentOrder）");
    }

    /**
     * 回调留痕集合（append-only 视图）。
     *
     * @return 留痕记录列表
     */
    public List<PaymentCallbackRecord> getCallbacks() {
        throw new UnsupportedOperationException("红阶段契约：getCallbacks 实现留绿阶段（PaymentOrder）");
    }
}