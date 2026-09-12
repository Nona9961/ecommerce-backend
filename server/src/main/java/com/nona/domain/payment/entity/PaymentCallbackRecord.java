package com.nona.domain.payment.entity;

import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

import java.time.Instant;
import java.util.List;

/**
 * 回调留痕记录（payment_callback_log 从表行，防线三的落库形态）：
 * 渠道回调原文的字段化快照，append-only 追加、创建后不可变（排查与对账
 * 依据，重复/失败回调同样留痕——对账不依赖迁移成败）。
 * <p>
 * 持久化形态：payment_callback_log 从表，以 paymentOrderId（rootId =
 * payment_order 主键）归属 PaymentOrder 聚合，由聚合仓储的从表机制
 * （getOther 装载 / 变更集驱动落库，CartItem/InventoryLog 判例同构）
 * 加载与追加；本实体无独立仓储、不宣称聚合根。行身份承载：独立
 * Snowflake 主键（id），无生命周期变更（append-only 实体——InventoryLog
 * 判例）。
 * <p>
 * 「回调原文」定义（mock 渠道载荷即标准化结构，字段化落库零信息
 * 损失）：{@code callbackType/payNo/refundNo/result/channelTxnNo/
 * amountCents} 六字段与渠道回调载荷同构 + {@code paymentOrderId} 归属 +
 * {@code occurredAt} 收到时间。真实渠道原始报文（raw JSON）为扩展预留位
 * （接真实渠道时仅需向本实体追加一列，契约演进只增不改）。
 * <p>
 * 形态不变式（守卫已收敛在构造路径实现）：
 * <ol>
 *     <li>必填字段（id/paymentOrderId/callbackType/payNo/result/
 *         channelTxnNo/occurredAt）缺失即非法形态，构造拒绝；</li>
 *     <li>类型与字段配套（CallbackType 配套约束）：PAY 回调 refundNo 必空、
 *         REFUND 回调 refundNo 必填（支付回调场景，退款流接续
 *         消费）；</li>
 *     <li>金额为正整数分（渠道回调金额已过正数校验，留痕原样保存）；</li>
 *     <li>不可变：无任何 setter/变更方法（测试反射钉死），追加与持久化
 *         由聚合方法 {@link PaymentOrder#appendCallbackRecord} 承载。</li>
 * </ol>
 *
 * @author nona9961
 */
public class PaymentCallbackRecord {

    /**
     * 留痕记录主键（Snowflake，payment_callback_log 主键）
     */
    private final Long id;

    /**
     * 归属支付单 ID（rootId：payment_order 主键，从表关联主表）
     */
    private final Long paymentOrderId;

    /**
     * 回调类型（渠道载荷原样：PAY/REFUND）
     */
    private final CallbackType callbackType;

    /**
     * 业务支付单号（渠道载荷原样回显）
     */
    private final String payNo;

    /**
     * 业务退款单号（渠道载荷原样；PAY 回调恒为空——配套约束）
     */
    private final String refundNo;

    /**
     * 渠道侧业务结果（SUCCESS/FAIL）
     */
    private final GatewayResult result;

    /**
     * 渠道流水号（支付回调 = 受理流水；唯一约束防线一的素材）
     */
    private final String channelTxnNo;

    /**
     * 回调金额（分，渠道实收；原样留痕，与支付单金额的一致性核对由聚合守卫承载）
     */
    private final long amountCents;

    /**
     * 回调收到时间（留痕时间线锚点）
     */
    private final Instant occurredAt;

    /**
     * 创建构造器（回调编排留痕第一位调用；形态守卫已收敛在构造路径
     * 实现）。
     *
     * @param id            留痕记录主键（Snowflake，必填）
     * @param paymentOrderId 归属支付单 ID（rootId，必填）
     * @param callbackType  回调类型（必填）
     * @param payNo         业务支付单号（必填非空）
     * @param refundNo      业务退款单号（PAY 回调必空；REFUND 回调必填）
     * @param result        渠道侧业务结果（必填）
     * @param channelTxnNo  渠道流水号（必填非空）
     * @param amountCents   回调金额（分，必为正整数）
     * @param occurredAt    回调收到时间（必填）
     */
    public PaymentCallbackRecord(Long id, Long paymentOrderId, CallbackType callbackType,
                                 String payNo, String refundNo, GatewayResult result,
                                 String channelTxnNo, long amountCents, Instant occurredAt) {
        BusinessAssert.assertNonNull(id, "留痕记录主键不能为空");
        BusinessAssert.assertNonNull(paymentOrderId, "归属支付单 ID 不能为空（rootId 必填）");
        BusinessAssert.assertNonNull(callbackType, "回调类型不能为空");
        BusinessAssert.assertTrue(payNo != null && !payNo.isBlank(), "业务支付单号不能为空");
        BusinessAssert.assertNonNull(result, "渠道侧业务结果不能为空");
        BusinessAssert.assertTrue(channelTxnNo != null && !channelTxnNo.isBlank(),
                "渠道流水号不能为空（留痕行缺关键字段无对账意义）");
        BusinessAssert.assertTrue(amountCents > 0, "回调金额必须为正整数分");
        BusinessAssert.assertNonNull(occurredAt, "回调收到时间不能为空");
        if (callbackType == CallbackType.REFUND && (refundNo == null || refundNo.isBlank())) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code(),
                    "REFUND 回调必须携带退款单号（类型与字段配套约束）");
        }
        if (callbackType == CallbackType.PAY && refundNo != null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code(),
                    "PAY 回调退款单号必须为空（类型与字段配套约束）");
        }
        this.id = id;
        this.paymentOrderId = paymentOrderId;
        this.callbackType = callbackType;
        this.payNo = payNo;
        this.refundNo = refundNo;
        this.result = result;
        this.channelTxnNo = channelTxnNo;
        this.amountCents = amountCents;
        this.occurredAt = occurredAt;
    }

    /**
     * 留痕记录主键。
     *
     * @return 主键
     */
    public Long getId() {
        return id;
    }

    /**
     * 归属支付单 ID（rootId）。
     *
     * @return 支付单 ID
     */
    public Long getPaymentOrderId() {
        return paymentOrderId;
    }

    /**
     * 回调类型。
     *
     * @return 回调类型
     */
    public CallbackType getCallbackType() {
        return callbackType;
    }

    /**
     * 业务支付单号（渠道载荷原样）。
     *
     * @return 支付单号
     */
    public String getPayNo() {
        return payNo;
    }

    /**
     * 业务退款单号（PAY 回调恒为空）。
     *
     * @return 退款单号，或 null（PAY 回调配套约束）
     */
    public String getRefundNo() {
        return refundNo;
    }

    /**
     * 渠道侧业务结果。
     *
     * @return 结果
     */
    public GatewayResult getResult() {
        return result;
    }

    /**
     * 渠道流水号。
     *
     * @return 渠道流水号
     */
    public String getChannelTxnNo() {
        return channelTxnNo;
    }

    /**
     * 回调金额（分）。
     *
     * @return 金额
     */
    public long getAmountCents() {
        return amountCents;
    }

    /**
     * 回调收到时间。
     *
     * @return 时间
     */
    public Instant getOccurredAt() {
        return occurredAt;
    }
}