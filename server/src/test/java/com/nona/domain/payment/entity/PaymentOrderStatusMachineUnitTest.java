package com.nona.domain.payment.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付单状态机场景测试（TD-11 防线二 + 状态机契约，红阶段）。
 * <p>
 * 覆盖（红阶段：失败原因 = 实现缺失——迁移方法体 UOE 抛直接 Error，
 * 或「期待 BusinessException 实抛 UOE」断言失败；绿阶段按本矩阵的
 * 目标行为实现后原样转绿）：
 * <ul>
 *     <li>Happy：待支付 → 已支付 / 已失败 / 已关闭；失败 → 关闭收口；
 *         已关闭重复关单幂等；留痕追加；</li>
 *     <li>Critical：同号重复回调幂等命中（B8.2 重复回调只生效一次）、
 *         金额不符拒绝、异号冲突（409，渠道事故优先）、终态再收回调；</li>
 *     <li>Fail：已支付关单拒绝、终态再迁移拒绝、参数防御（空白流水号）。</li>
 * </ul>
 */
class PaymentOrderStatusMachineUnitTest {

    /**
     * 被测支付单（创建构造器装配，金额 10000 分 = 100 元）。
     */
    private final PaymentOrder order = new PaymentOrder(1L, "PAY202609070001", 100L, 10000L,
            "MOCK", Instant.parse("2026-09-07T10:00:00Z"));

    // ------------------------------------------------------------------
    // Happy path（主流程成功迁移）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("happy-1 支付成功回调：待支付 → 已支付，流水落位")
    void markPaid_pendingToPaid() {
        order.markPaid("TXN-ALIPAY-001", 10000L);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(order.getChannelTxnNo()).isEqualTo("TXN-ALIPAY-001");
    }

    @Test
    @DisplayName("happy-2 支付失败回调：待支付 → 已失败（订单侧停留待支付等待超时）")
    void markFailed_pendingToFailed() {
        order.markFailed("TXN-ALIPAY-002", 10000L);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.FAILED);
        assertThat(order.getChannelTxnNo()).isEqualTo("TXN-ALIPAY-002");
    }

    @Test
    @DisplayName("happy-3 待支付关单（主动取消/支付超时）：→ 已关闭")
    void close_pendingToClosed() {
        order.close();
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED);
    }

    @Test
    @DisplayName("happy-4 失败单显式收口：已失败 → 已关闭（超时编排打向失败单）")
    void close_failedToClosed() {
        order.markFailed("TXN-ALIPAY-003", 10000L);
        order.close();
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED);
    }

    @Test
    @DisplayName("happy-5 重复关单幂等：已关闭再关单成功返回（超时与取消重放不报错）")
    void close_closedIdempotent() {
        order.close();
        order.close();
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED);
    }

    @Test
    @DisplayName("happy-6 留痕追加：回调原文 append-only 追加到留痕集合")
    void appendCallbackRecord_recordAppended() {
        final PaymentCallbackRecord record = new PaymentCallbackRecord(
                11L, 1L, com.nona.domain.payment.ports.CallbackType.PAY, "PAY202609070001",
                null, com.nona.domain.payment.ports.GatewayResult.SUCCESS,
                "TXN-ALIPAY-001", 10000L, Instant.parse("2026-09-07T10:01:00Z"));
        order.appendCallbackRecord(record);
        assertThat(order.getCallbacks()).containsExactly(record);
    }

    // ------------------------------------------------------------------
    // Critical path（重复回调/金额/流水边界）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("critical-1 同号重复回调：幂等命中（status_illegal），编排按已处理应答——只生效一次")
    void markPaid_duplicateCallbackSameTxn_rejectedAsIdempotent() {
        order.markPaid("TXN-ALIPAY-001", 10000L);
        assertThatThrownBy(() -> order.markPaid("TXN-ALIPAY-001", 10000L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_STATUS_ILLEGAL.code());
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(order.getChannelTxnNo()).isEqualTo("TXN-ALIPAY-001");
    }

    @Test
    @DisplayName("critical-2 金额不符：回调金额 ≠ 支付单金额拒绝（amount_mismatch），不入账")
    void markPaid_amountMismatch_rejected() {
        assertThatThrownBy(() -> order.markPaid("TXN-ALIPAY-001", 9999L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_AMOUNT_MISMATCH.code());
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PENDING_PAYMENT);
        assertThat(order.getChannelTxnNo()).isNull();
    }

    @Test
    @DisplayName("critical-3 异号冲突：流水号已占用且异号 → callback_duplicate（409，渠道事故优先诊断）")
    void markPaid_channelTxnConflict_rejected() {
        order.markPaid("TXN-ALIPAY-001", 10000L);
        assertThatThrownBy(() -> order.markPaid("TXN-WECHAT-999", 10000L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_CALLBACK_DUPLICATE.code());
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
    }

    @Test
    @DisplayName("critical-4 已支付再收失败回调（同号）：终态拒绝 status_illegal")
    void markFailed_afterPaidSameTxn_rejected() {
        order.markPaid("TXN-ALIPAY-001", 10000L);
        assertThatThrownBy(() -> order.markFailed("TXN-ALIPAY-001", 10000L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_STATUS_ILLEGAL.code());
    }

    @Test
    @DisplayName("critical-5 失败单再收异号成功回调：异号冲突优先（409）——失败单不允许异号翻正")
    void markPaid_afterFailedWithOtherTxn_rejectedAsDuplicate() {
        order.markFailed("TXN-ALIPAY-002", 10000L);
        assertThatThrownBy(() -> order.markPaid("TXN-ALIPAY-999", 10000L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_CALLBACK_DUPLICATE.code());
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.FAILED);
    }

    @Test
    @DisplayName("critical-6 失败单再收同号回调：终态拒绝 status_illegal（幂等命中）")
    void markFailed_afterFailedSameTxn_rejected() {
        order.markFailed("TXN-ALIPAY-002", 10000L);
        assertThatThrownBy(() -> order.markFailed("TXN-ALIPAY-002", 10000L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_STATUS_ILLEGAL.code());
    }

    @Test
    @DisplayName("critical-7 失败回调金额不符：同样拒绝 amount_mismatch（成功/失败回调同构守卫）")
    void markFailed_amountMismatch_rejected() {
        assertThatThrownBy(() -> order.markFailed("TXN-ALIPAY-002", 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_AMOUNT_MISMATCH.code());
    }

    // ------------------------------------------------------------------
    // Fail path（非法迁移/参数防御）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fail-1 已支付关单拒绝：资金终态不可关单（关单走退款流）")
    void close_afterPaid_rejected() {
        order.markPaid("TXN-ALIPAY-001", 10000L);
        assertThatThrownBy(order::close)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_STATUS_ILLEGAL.code());
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
    }

    @Test
    @DisplayName("fail-2 终态再迁移拒绝：已关闭收 markPaid → status_illegal")
    void markPaid_afterClosed_rejected() {
        order.close();
        assertThatThrownBy(() -> order.markPaid("TXN-ALIPAY-001", 10000L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_STATUS_ILLEGAL.code());
    }

    @Test
    @DisplayName("fail-3 空白渠道流水号：参数防御拒绝（非空断言）")
    void markPaid_blankTxnNo_rejected() {
        assertThatThrownBy(() -> order.markPaid("  ", 10000L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> order.markPaid(null, 10000L))
                .isInstanceOf(BusinessException.class);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PENDING_PAYMENT);
    }
}