package com.nona.domain.payment.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 退款单聚合根（refund_order 主表行，买家维度 global，本阶段冻结）：
 * 一次退款流程的资金侧事实——退款单号/关联支付单号/操作单元子单/
 * 金额/状态机/渠道退款流水/发货时点快照 + 回调留痕集合（三层
 * 幂等防线在退款面的同构承载）。
 * <p>
 * 持久化形态（契约声明）：refund_order 主表（global，独立
 * Snowflake 主键；refund_no 唯一——业务退款单号；sub_order_id
 * 唯一——一子单一生至多一个退款单，防重复申请的 DB 物理兜底；
 * channel_refund_txn_no 无唯一约束——重试受理换渠道流水覆盖更新，
 * 唯一约束会拦覆盖；不建超时索引——退款无超时调度面）。回调留痕为
 * 从表 refund_callback_log，以 rootId（refund_order 主键）归属本
 * 聚合，由聚合仓储的从表机制（getOther 装载/变更集驱动落库，
 * PaymentOrder/payment_callback_log 判例同构）加载与追加，无独立
 * 仓储。变更落库适配 DifferRepository：读 → track 快照 → save →
 * 变更集驱动落库。
 * <p>
 * 退款单与支付单/订单衔接：退款单承载<b>资金侧退款状态机</b>（
 * PENDING→SUCCEEDED/FAILED，领域模型冻结：「funds status is carried
 * by RefundOrder only」——支付单 PAID 保持资金已收终态不迁移）；
 * 订单承载履约侧状态（子单 退款中→已退款 / 发货超时 已支付→已关闭，
 * 主单派生）——两侧在退款编排同事务推进（refund.markSucceeded →
 * order.completeRefund → 未发货库存回补），退款编排接线。退款单仅以
 * subOrderId 引用子单（操作单元）、payNo 引用支付单（原交易定位，
 * 跨聚合引用 ID 协作），不加载订单/支付单对象。
 * <p>
 * 关键不变量（全部收敛在本聚合方法内，包外无字段变更路径——除
 * status/channelRefundTxnNo 两个可变位经迁移方法变更外，字段全
 * final）：
 * <ol>
 *     <li><b>状态机单向（退款面防线二）</b>：PENDING 可出（→ SUCCEEDED/
 *         FAILED），FAILED → PENDING（重试受理归位），SUCCEEDED 终态
 *         无出边——非法迁移拒绝（{@code payment.refund_status_illegal}）；
 *         重复受理（PENDING 已落位流水再受理）同为非法迁移拒绝；</li>
 *     <li><b>金额一致性</b>：退款回调金额必须 = 退款单金额（= 子单实付，
 *         创建时固化防中途改价，退款金额=实付金额）——不符拒绝
 *         （{@code payment.amount_mismatch}），不做半额/超额入账；</li>
 *     <li><b>渠道流水号占用一致（退款面防线一领域位）</b>：退款单流水
 *         已落位且回调异号即渠道事故，拒绝（{@code payment.callback_duplicate}，
 *         409）；同号重复回调由状态守卫拒绝（幂等命中）；</li>
 *     <li><b>回调原文每笔留痕（退款面防线三）</b>：退款回调记录
 *         append-only，先留痕后判迁移（重复/失败/金额不符同样留痕可查），
 *         对账不依赖迁移成败；</li>
 *     <li><b>一子单一退款单</b>：sub_order_id 唯一约束 + 申请编排「防重
 *         前置判定」守护（已存在退款单（含 FAILED 可重试态）再申请拒绝
 *         {@code payment.refund_duplicate}，重试走既有单）。</li>
 * </ol>
 * <p>
 * 迁移守卫判定顺序（markSucceeded 与 markRefundFailed 同构，
 * 钉死契约）：① 流水号已占用且异号 → {@code payment.callback_duplicate}
 * （渠道事故优先诊断）；② 状态非 PENDING → {@code payment.refund_status_illegal}
 * （同号重复回调幂等命中同此码，编排捕获按已处理应答）；③ 金额不符 →
 * {@code payment.amount_mismatch}（成功回调专属守卫，失败回调不校验
 * 金额）；④ 迁移 + 流水落位。
 * <p>
 * 创建必须经由 {@link com.nona.domain.payment.factory.RefundOrderFactory}
 * （ID/refundNo 生成收敛工厂一处）；装载（仓储重建：留痕集合按
 * refund_order_id 反查）走装载构造器。两构造器仅字段定型，
 * 形态守卫（必填/非空/非负）按 javadoc 契约实现。
 *
 * @author nona9961
 */
public class RefundOrder {

    /**
     * 退款单主键（Snowflake，refund_order 主键，聚合根标识）
     */
    private final Long id;

    /**
     * 退款单号（REF + 日期 + snowflake 后段，refund_no 唯一；
     * 渠道受理幂等键——同一退款单只受理一次）
     */
    private final String refundNo;

    /**
     * 关联支付单号（原交易定位：gateway.refund 入参与累计口径锚点；
     * 引用 ID 协作，不加载支付单对象）
     */
    private final String payNo;

    /**
     * 操作单元子单 ID（退款申请/回补/订单推进的子单维度锚点；
     * sub_order_id 唯一约束——一子单一退款单）
     */
    private final Long subOrderId;

    /**
     * 退款金额（分，= 子单实付，创建时固化；退款金额=实付金额）
     */
    private final long amount;

    /**
     * 申请时刻是否已发货快照（回补判定锚点：false = 未发货退款 →
     * 已售回补可售；true = 已发货/已完成退款 → 不回补，
     * 退货物流售后深化留接口位——快照固化防退款流程中订单状态
     * 再演进污染判定）
     */
    private final boolean shippedAtApply;

    /**
     * 申请原因（买家输入，可空——取消编排 reason 同先例；不承载语义
     * 默认值，空即无原因）
     */
    private final String reason;

    /**
     * 回调留痕集合（append-only；创建恒为空集合——非空设计，无 null 语义）
     */
    private final List<RefundCallbackRecord> callbacks;

    /**
     * 退款单状态（唯一可变位之一：仅经迁移方法变更）
     */
    private RefundOrderStatus status;

    /**
     * 渠道退款流水号（唯一可变位之二：受理成功落位；重试受理覆盖更新
     * ——故不建唯一约束；NULL = 尚未受理成功的时间点语义，非「默认值」
     * 替换）
     */
    private String channelRefundTxnNo;

    /**
     * 创建构造器（仅工厂路径）：退款中定型 + 空留痕集合 + 未受理流水。
     *
     * @param id            退款单主键（Snowflake）
     * @param refundNo      退款单号（规则，必填非空）
     * @param payNo         关联支付单号（必填非空）
     * @param subOrderId    操作单元子单 ID（必填）
     * @param amount        退款金额（分，= 子单实付，必为正）
     * @param shippedAtApply 申请时刻已发货快照（必填）
     * @param reason        申请原因（可空）
     */
    public RefundOrder(Long id, String refundNo, String payNo, Long subOrderId,
                       long amount, boolean shippedAtApply, String reason) {
        BusinessAssert.assertNonNull(id, "退款单主键不能为空");
        BusinessAssert.assertTrue(refundNo != null && !refundNo.isBlank(), "退款单号不能为空");
        BusinessAssert.assertTrue(payNo != null && !payNo.isBlank(), "关联支付单号不能为空");
        BusinessAssert.assertNonNull(subOrderId, "操作单元子单 ID 不能为空（一子单一退款单锚点）");
        BusinessAssert.assertTrue(amount > 0, "退款金额必须为正（= 子单实付）");
        this.id = id;
        this.refundNo = refundNo;
        this.payNo = payNo;
        this.subOrderId = subOrderId;
        this.amount = amount;
        this.shippedAtApply = shippedAtApply;
        this.reason = reason;
        this.callbacks = new ArrayList<>();
        this.status = RefundOrderStatus.PENDING;
        this.channelRefundTxnNo = null;
    }

    /**
     * 装载构造器（仅仓储重建/转换器装载调用）：以持久化状态恢复聚合
     * （状态/流水/留痕集合为持久化值；留痕集合按 refund_order_id 反查
     * 装载）。形态守卫（必填/非空/金额非正/集合组装防御）按
     * javadoc 契约实现；装载不执行写路径校验。
     *
     * @param id                退款单主键
     * @param refundNo          退款单号
     * @param payNo             关联支付单号
     * @param subOrderId        操作单元子单 ID
     * @param amount            退款金额（分）
     * @param shippedAtApply    申请时刻已发货快照
     * @param reason            申请原因（可空）
     * @param status            持久化状态
     * @param channelRefundTxnNo 持久化渠道退款流水号（NULL = 未受理成功）
     * @param callbacks         持久化留痕集合（必填，可为空集合）
     */
    public RefundOrder(Long id, String refundNo, String payNo, Long subOrderId,
                       long amount, boolean shippedAtApply, String reason,
                       RefundOrderStatus status, String channelRefundTxnNo,
                       List<RefundCallbackRecord> callbacks) {
        BusinessAssert.assertNonNull(id, "退款单主键不能为空");
        BusinessAssert.assertTrue(refundNo != null && !refundNo.isBlank(), "退款单号不能为空");
        BusinessAssert.assertTrue(payNo != null && !payNo.isBlank(), "关联支付单号不能为空");
        BusinessAssert.assertNonNull(subOrderId, "操作单元子单 ID 不能为空（一子单一退款单锚点）");
        BusinessAssert.assertTrue(amount > 0, "退款金额必须为正（= 子单实付）");
        BusinessAssert.assertNonNull(status, "退款单状态不能为空");
        BusinessAssert.assertNonNull(callbacks, "回调留痕集合不能为空（装载必填，可为空集合）");
        this.id = id;
        this.refundNo = refundNo;
        this.payNo = payNo;
        this.subOrderId = subOrderId;
        this.amount = amount;
        this.shippedAtApply = shippedAtApply;
        this.reason = reason;
        this.status = status;
        this.channelRefundTxnNo = channelRefundTxnNo;
        this.callbacks = new ArrayList<>(callbacks);
    }

    /**
     * 退款受理成功落位（PENDING 首次受理 / FAILED 重试受理归位，统一入口）：
     * <p>
     * 语义钉死：受理成功 ≠ 退款成功——渠道异步回调模型（受理只表达
     * 「受理」，结果经 REFUND 回调异步到达），本方法仅落位渠道退款流水
     * 并推进状态（FAILED → PENDING 重试归位）。守卫：
     * <ul>
     *     <li>SUCCEEDED（终态）→ {@code payment.refund_status_illegal}
     *         （资金已退不可逆，拒绝任何受理动作）；</li>
     *     <li>PENDING 且流水已落位 → {@code payment.refund_status_illegal}
     *         （已受理过，防重复受理重放——渠道幂等键 refundNo 兜底并发
     *         窗口，业务侧不重复问渠）；</li>
     *     <li>PENDING 未落位 / FAILED → 流水落位（FAILED 重试覆盖更新，
     *         channel_refund_txn_no 不建唯一约束的原因）。</li>
     * </ul>
     * 流水号必填非空（渠道受理即生成，业务侧唯一约束防线的素材）。
     *
     * @param channelRefundTxnNo 渠道退款流水号（必填非空；重试受理为新流水）
     */
    public void recordAcceptance(String channelRefundTxnNo) {
        if (channelRefundTxnNo == null || channelRefundTxnNo.isBlank()) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code(),
                    "渠道退款流水号必填非空（受理成功必落位）");
        }
        if (status == RefundOrderStatus.SUCCEEDED) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code(),
                    "已退款终态不可再受理（资金已退还不可逆，拒绝任何受理动作）");
        }
        if (status == RefundOrderStatus.PENDING && this.channelRefundTxnNo != null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code(),
                    "已受理过（退款中 + 流水已落位），重复受理拒绝——渠道幂等键 refundNo 兜底并发窗口");
        }
        this.channelRefundTxnNo = channelRefundTxnNo;
        if (status == RefundOrderStatus.FAILED) {
            this.status = RefundOrderStatus.PENDING;
        }
    }

    /**
     * 退款失败迁移（PENDING → FAILED；受理拒绝与退款失败回调共用）：
     * <p>
     * 语义钉死：受理拒绝（渠道 accepted=false，无后续回调）或 REFUND
     * 失败回调落位 FAILED——订单侧停留退款中（领域模型「failure keeps
     * order in refunding (retryable)」），买家对同一退款单重试受理。
     * 守卫：非 PENDING（SUCCEEDED 终态 / FAILED 重复失败回调幂等命中）
     * → {@code payment.refund_status_illegal}——同号重复失败回调由编排
     * 捕获后按已处理应答，不重放。
     */
    public void markRefundFailed() {
        if (status != RefundOrderStatus.PENDING) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code(),
                    "仅退款中可标记失败（重复失败回调幂等命中或终态再迁移）");
        }
        this.status = RefundOrderStatus.FAILED;
    }

    /**
     * 退款成功回调迁移（PENDING → SUCCEEDED）：
     * <p>
     * 退款面防线二状态守卫 + 金额一致性与流水号占用守卫（判定顺序见类
     * javadoc）：① 流水号已占用且异号 → 409 渠道事故；② 非 PENDING 拒绝
     * （同号重复回调幂等命中同此码，编排捕获后按已处理应答——重复
     * 回调只生效一次，不重放订单/库存编排）；③ 金额不符拒绝；④
     * 迁移。成功回调的订单/库存推进（order.completeRefund / 未发货
     * restore）由退款回调编排同事务接线（退款回调编排），本方法只
     * 收敛资金侧迁移。
     * <p>
     * 调用时序契约：编排先 {@link #appendCallbackRecord} 留痕（防线三，
     * 对账不依赖迁移成败），再调用本方法。
     *
     * @param channelRefundTxnNo 渠道退款流水号（必填非空）
     * @param callbackAmountCents 回调金额（分，必须 = 退款单金额）
     */
    public void markSucceeded(String channelRefundTxnNo, long callbackAmountCents) {
        if (channelRefundTxnNo == null || channelRefundTxnNo.isBlank()) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code(),
                    "渠道退款流水号不能为空（留痕与一致防线素材）");
        }
        if (this.channelRefundTxnNo != null && !this.channelRefundTxnNo.equals(channelRefundTxnNo)) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_CALLBACK_DUPLICATE.code(),
                    "渠道流水号已占用且异号（渠道事故优先诊断）：已占位 " + this.channelRefundTxnNo
                            + "，本次 " + channelRefundTxnNo);
        }
        if (status != RefundOrderStatus.PENDING) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code(),
                    "仅退款中可标记退款成功（同号重复回调幂等命中或终态再迁移）");
        }
        if (callbackAmountCents != this.amount) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_AMOUNT_MISMATCH.code(),
                    "回调金额与退款单金额不符：回调 " + callbackAmountCents + "，退款单 " + this.amount);
        }
        this.status = RefundOrderStatus.SUCCEEDED;
        this.channelRefundTxnNo = channelRefundTxnNo;
    }

    /**
     * 追加退款回调留痕记录（退款面防线三，append-only）：
     * <p>
     * 时序契约：退款回调编排在状态迁移<b>之前</b>追加（先留痕后判迁移）——
     * 重复/失败/金额不符等被拒绝的回调同样留痕，排查与对账不依赖迁移
     * 成败。后续回调追加到集合尾部，记录不可变（无移除/修改路径）。
     *
     * @param record 退款回调留痕记录（必填，形态守卫由记录构造路径承载）
     */
    public void appendCallbackRecord(RefundCallbackRecord record) {
        if (record == null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code(),
                    "退款回调留痕记录不能为空（防线三留痕素材形态防御）");
        }
        this.callbacks.add(record);
    }

    /**
     * 退款单主键。
     *
     * @return 主键
     */
    public Long getId() {
        return id;
    }

    /**
     * 退款单号。
     *
     * @return 退款单号
     */
    public String getRefundNo() {
        return refundNo;
    }

    /**
     * 关联支付单号。
     *
     * @return 支付单号
     */
    public String getPayNo() {
        return payNo;
    }

    /**
     * 操作单元子单 ID。
     *
     * @return 子单 ID
     */
    public Long getSubOrderId() {
        return subOrderId;
    }

    /**
     * 退款金额（分，= 子单实付）。
     *
     * @return 金额
     */
    public long getAmount() {
        return amount;
    }

    /**
     * 申请时刻已发货快照（回补判定锚点）。
     *
     * @return true = 申请时已发货（不退库存）；false = 未发货（回补）
     */
    public boolean isShippedAtApply() {
        return shippedAtApply;
    }

    /**
     * 申请原因（可空）。
     *
     * @return 原因，或 null
     */
    public String getReason() {
        return reason;
    }

    /**
     * 退款单状态。
     *
     * @return 状态
     */
    public RefundOrderStatus getStatus() {
        return status;
    }

    /**
     * 渠道退款流水号（NULL = 尚未受理成功）。
     *
     * @return 渠道流水号，或 null
     */
    public String getChannelRefundTxnNo() {
        return channelRefundTxnNo;
    }

    /**
     * 回调留痕集合（append-only 视图：仅聚合内部可追加，包外只读）。
     *
     * @return 留痕记录列表（不可变视图）
     */
    public List<RefundCallbackRecord> getCallbacks() {
        return Collections.unmodifiableList(callbacks);
    }
}