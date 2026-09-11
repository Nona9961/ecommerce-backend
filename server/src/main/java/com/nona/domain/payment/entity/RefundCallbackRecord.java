package com.nona.domain.payment.entity;

import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

import java.time.Instant;

/**
 * 退款回调留痕记录（RefundOrder 聚合从表 refund_callback_log 行，
 * rootId = refund_order 主键，append-only）：退款回调原文留痕——先留痕
 * 后判迁移（防线三在退款面的同构承载），重复/失败/金额不符等
 * 被拒回调同样留痕，对账不依赖迁移成败。
 * <p>
 * 与支付回调留痕 {@link PaymentCallbackRecord} 字段同构（类型/支付单号/
 * 退款单号/结果/渠道流水/金额/时间），独立建模不跨聚合复用——从表以
 * rootId 归属各自聚合（refund_callback_log.refund_order_id vs
 * payment_callback_log.payment_order_id），DDL 归属清晰；同构抽取属
 * 整理范畴（可选项）。
 * <p>
 * 不变量（构造守卫，全部收敛在构造路径，按 javadoc 契约
 * 实现）：id/rootId/类型/支付单号/结果/渠道流水号/收到时间必填；金额
 * 必为正整数；类型与字段配套——REFUND 回调退款单号必填（本记录型
 * 专用于退款回调场景）。仅字段定型。
 *
 * @author nona9961
 */
public class RefundCallbackRecord {

    /**
     * 留痕记录主键（Snowflake，refund_callback_log 主键）
     */
    private final Long id;

    /**
     * 归属退款单 ID（rootId，refund_callback_log.refund_order_id 归属列）
     */
    private final Long refundOrderId;

    /**
     * 回调类型（退款留痕恒为 REFUND）
     */
    private final CallbackType callbackType;

    /**
     * 业务支付单号（回调原样回显，对账锚点）
     */
    private final String payNo;

    /**
     * 业务退款单号（REFUND 回调必填）
     */
    private final String refundNo;

    /**
     * 渠道侧业务结果（SUCCESS/FAIL，退款结果）
     */
    private final GatewayResult result;

    /**
     * 渠道退款流水号（受理与回调同一流水，业务侧流水一致防线素材）
     */
    private final String channelTxnNo;

    /**
     * 回调金额（分，渠道实退；原样留痕，与退款单金额的一致性核对由
     * 聚合守卫承载）
     */
    private final long amountCents;

    /**
     * 回调收到时间（留痕时间线锚点）
     */
    private final Instant occurredAt;

    /**
     * 创建构造器（退款回调编排留痕第一位调用；形态守卫按 javadoc 契约
     * 实现，仅字段定型）。
     *
     * @param id            留痕记录主键（Snowflake，必填）
     * @param refundOrderId 归属退款单 ID（rootId，必填）
     * @param callbackType  回调类型（必填，本记录恒为 REFUND）
     * @param payNo         业务支付单号（必填非空）
     * @param refundNo      业务退款单号（必填非空——REFUND 类型配套约束）
     * @param result        渠道侧业务结果（必填）
     * @param channelTxnNo  渠道流水号（必填非空）
     * @param amountCents   回调金额（分，必为正整数）
     * @param occurredAt    回调收到时间（必填）
     */
    public RefundCallbackRecord(Long id, Long refundOrderId, CallbackType callbackType,
                                String payNo, String refundNo, GatewayResult result,
                                String channelTxnNo, long amountCents, Instant occurredAt) {
        BusinessAssert.assertNonNull(id, "留痕记录主键不能为空");
        BusinessAssert.assertNonNull(refundOrderId, "归属退款单 ID 不能为空（rootId 必填）");
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
        this.refundOrderId = refundOrderId;
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
     * 归属退款单 ID（rootId）。
     *
     * @return 退款单 ID
     */
    public Long getRefundOrderId() {
        return refundOrderId;
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
     * 业务支付单号。
     *
     * @return 支付单号
     */
    public String getPayNo() {
        return payNo;
    }

    /**
     * 业务退款单号。
     *
     * @return 退款单号
     */
    public String getRefundNo() {
        return refundNo;
    }

    /**
     * 渠道侧业务结果。
     *
     * @return 退款结果
     */
    public GatewayResult getResult() {
        return result;
    }

    /**
     * 渠道退款流水号。
     *
     * @return 流水号
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