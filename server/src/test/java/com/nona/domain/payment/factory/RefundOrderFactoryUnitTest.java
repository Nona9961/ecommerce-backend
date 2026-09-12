package com.nona.domain.payment.factory;

import com.nona.domain.payment.entity.RefundOrder;
import com.nona.domain.payment.entity.RefundOrderStatus;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 退款单工厂场景测试（单号规则 + 创建形态契约）。
 * <p>
 * 覆盖：happy——创建定型（退款中状态/金额 = 子单实付/未发货快照/原因
 * 透传/空留痕集合）；critical——已发货申请快照定型（回补判定锚点）；
 * fail——形态守卫（子单缺失/退款金额非正/支付单号空白）。
 */
class RefundOrderFactoryUnitTest {

    /**
     * 被测工厂（无状态，测试内直接装配）。
     */
    private final RefundOrderFactory factory = new RefundOrderFactory();

    /* ================= happy path ================= */

    /**
     * happy-1 创建退款单（未发货申请）：退款中定型 + 金额 = 子单实付
     * + 未发货快照回显 + 原因透传 + 留痕集合空（非空设计）。
     */
    @Test
    @DisplayName("创建退款单（未发货）：退款中定型 + 金额/快照/原因定型")
    void create_notShipped_initialized() {
        final RefundOrder order = factory.create(
                "PAY202609070001", 101L, 10000L, false, "不想要了");
        assertThat(order.getStatus()).isEqualTo(RefundOrderStatus.PENDING);
        assertThat(order.getPayNo()).isEqualTo("PAY202609070001");
        assertThat(order.getSubOrderId()).isEqualTo(101L);
        assertThat(order.getAmount()).isEqualTo(10000L);
        assertThat(order.isShippedAtApply()).isFalse();
        assertThat(order.getReason()).isEqualTo("不想要了");
        assertThat(order.getCallbacks()).isEmpty();
        assertThat(order.getChannelRefundTxnNo()).isNull();
    }

    /**
     * happy-2 退款单号规则：REF 前缀（REF + 日期段 + snowflake，
     * refund_no 唯一兜底）。
     */
    @Test
    @DisplayName("退款单号规则：REF 前缀")
    void create_refundNoRule() {
        final RefundOrder order = factory.create(
                "PAY202609070001", 101L, 10000L, false, null);
        assertThat(order.getRefundNo()).startsWith("REF");
    }

    /* ================= critical path ================= */

    /**
     * critical-1 已发货申请快照：shippedAtApply = true 定型（已发货退款
     * 不回补库存——回补判定锚点固化在申请时刻）。
     */
    @Test
    @DisplayName("已发货申请：已发货快照定型（不回补判定锚点）")
    void create_shipped_trueSnapshot() {
        final RefundOrder order = factory.create(
                "PAY202609070001", 101L, 10000L, true, "货不对板");
        assertThat(order.isShippedAtApply()).isTrue();
    }

    /* ================= fail path ================= */

    /**
     * fail-1 子单缺失拒绝：退款以子单为操作单元（一子单一退款单锚点），
     * 子单 ID 必填。
     */
    @Test
    @DisplayName("子单缺失拒绝：refund_invalid")
    void create_subOrderMissing_rejected() {
        assertThatThrownBy(() -> factory.create("PAY202609070001", null, 10000L, false, null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_INVALID.code()));
    }

    /**
     * fail-2 退款金额非正拒绝：退款金额 = 子单实付必为正（0 与负无业务
     * 意义）。
     */
    @Test
    @DisplayName("退款金额非正拒绝：refund_invalid")
    void create_nonPositiveAmount_rejected() {
        assertThatThrownBy(() -> factory.create("PAY202609070001", 101L, 0L, false, null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_INVALID.code()));
    }

    /**
     * fail-3 支付单号空白拒绝：原交易定位锚点（gateway.refund 入参），
     * 必填非空。
     */
    @Test
    @DisplayName("支付单号空白拒绝：refund_invalid")
    void create_blankPayNo_rejected() {
        assertThatThrownBy(() -> factory.create("  ", 101L, 10000L, false, null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_INVALID.code()));
    }
}