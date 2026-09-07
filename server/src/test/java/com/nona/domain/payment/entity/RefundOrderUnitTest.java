package com.nona.domain.payment.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 退款单聚合状态机场景测试（退款面防线二 + 状态机契约，红阶段——
 * 对齐领域模型冻结 Refund Aggregate：pending → succeeded / failed
 * （failed is retryable））。
 * <p>
 * 覆盖（红阶段：失败原因 = 实现缺失——迁移方法体 UOE 抛直接 Error，
 * 或「期待 BusinessException 实抛 UOE」断言失败；绿阶段按本矩阵的
 * 目标行为实现后原样转绿）：
 * <ul>
 *     <li>Happy：受理落位（PENDING 保持 + 流水落位）、成功回调 →
 *         SUCCEEDED（终态）、失败迁移 → FAILED、失败重试受理归位
 *         （FAILED → PENDING + 新流水覆盖）、留痕追加（append-only）；</li>
 *     <li>Critical：重复受理拒绝（已落位 PENDING 再受理）、终态拒绝所有
 *         受理动作、FAILED 重复失败回调幂等命中、重试流水覆盖；</li>
 *     <li>Fail：异号流水冲突（409 渠道事故优先）、金额不符拒绝、留痕
 *         null 拒绝、空白流水拒绝。</li>
 * </ul>
 */
class RefundOrderUnitTest {

    /**
     * 被测退款单基线（创建构造器装配：关联支付单/操作单元子单/金额
     * 10000 分 = 100 元/未发货快照/原因）。
     */
    private static RefundOrder refundOrder() {
        return new RefundOrder(1L, "REF202609070001", "PAY202609070001", 101L,
                10000L, false, "不想要了");
    }

    /* ================= happy path ================= */

    /**
     * happy-1 受理落位：受理成功 ≠ 退款成功（异步回调模型）——状态保持
     * 退款中 + 渠道退款流水落位。
     */
    @Test
    @DisplayName("受理成功：退款中保持 + 渠道退款流水落位（受理 ≠ 退款结果）")
    void recordAcceptance_pendingWithTxnNo() {
        final RefundOrder order = refundOrder();
        order.recordAcceptance("TXN-REFUND-001");
        assertThat(order.getStatus()).isEqualTo(RefundOrderStatus.PENDING);
        assertThat(order.getChannelRefundTxnNo()).isEqualTo("TXN-REFUND-001");
    }

    /**
     * happy-2 退款成功回调：退款中 → 已退款（终态；B8.5 ② 订单侧置已退款
     * 由编排推进，本方法收敛资金侧迁移）。
     */
    @Test
    @DisplayName("退款成功回调：退款中 → 已退款（终态），流水落位")
    void markSucceeded_pendingToSucceeded() {
        final RefundOrder order = refundOrder();
        order.recordAcceptance("TXN-REFUND-001");
        order.markSucceeded("TXN-REFUND-001", 10000L);
        assertThat(order.getStatus()).isEqualTo(RefundOrderStatus.SUCCEEDED);
        assertThat(order.getChannelRefundTxnNo()).isEqualTo("TXN-REFUND-001");
    }

    /**
     * happy-3 退款失败迁移（受理拒绝与失败回调共用）：退款中 → 已失败
     * （可重试——订单侧停留退款中，由重试受理归位）。
     */
    @Test
    @DisplayName("退款失败：退款中 → 已失败（可重试，信息留活性）")
    void markRefundFailed_pendingToFailed() {
        final RefundOrder order = refundOrder();
        order.markRefundFailed();
        assertThat(order.getStatus()).isEqualTo(RefundOrderStatus.FAILED);
    }

    /**
     * happy-4 失败重试受理归位：已失败 → 退款中 + 新流水覆盖（FAILED
     * 重试复用同一退款单与 refundNo，渠道幂等键——领域模型「failed is
     * retryable」；流水覆盖更新是 channel_refund_txn_no 不建唯一约束
     * 的原因）。
     */
    @Test
    @DisplayName("失败重试受理：已失败 → 退款中 + 新流水覆盖")
    void recordAcceptance_failedRetryBackToPending() {
        final RefundOrder order = refundOrder();
        order.recordAcceptance("TXN-REFUND-001");
        order.markRefundFailed();
        order.recordAcceptance("TXN-REFUND-002");
        assertThat(order.getStatus()).isEqualTo(RefundOrderStatus.PENDING);
        assertThat(order.getChannelRefundTxnNo()).isEqualTo("TXN-REFUND-002");
    }

    /**
     * happy-5 留痕追加：退款回调原文 append-only 追加到留痕集合
     * （防线三：先留痕后判迁移——被拒回调同样留痕）。
     */
    @Test
    @DisplayName("留痕追加：回调原文 append-only（先留痕后判迁移）")
    void appendCallbackRecord_recordAppended() {
        final RefundOrder order = refundOrder();
        order.appendCallbackRecord(new RefundCallbackRecord(11L, 1L, CallbackType.REFUND,
                "PAY202609070001", "REF202609070001", GatewayResult.SUCCESS,
                "TXN-REFUND-001", 10000L, Instant.now()));
        assertThat(order.getCallbacks()).hasSize(1);
    }

    /* ================= critical path ================= */

    /**
     * critical-1 重复受理拒绝：已受理成功的退款中单再受理（PENDING +
     * 流水已落位）→ refund_status_illegal——防重复问渠重放（并发窗口由
     * 渠道幂等键 refundNo 兜底，业务侧不重复受理）。
     */
    @Test
    @DisplayName("重复受理拒绝：已落位流水再受理 → refund_status_illegal")
    void recordAcceptance_duplicateRejected() {
        final RefundOrder order = refundOrder();
        order.recordAcceptance("TXN-REFUND-001");
        assertThatThrownBy(() -> order.recordAcceptance("TXN-REFUND-003"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code()));
    }

    /**
     * critical-2 终态受理拒绝：已退款（SUCCEEDED 终态，资金不可逆）再
     * 受理 → refund_status_illegal。
     */
    @Test
    @DisplayName("已退款终态受理拒绝：SUCCEEDED 无出边")
    void recordAcceptance_succeededRejected() {
        final RefundOrder order = refundOrder();
        order.recordAcceptance("TXN-REFUND-001");
        order.markSucceeded("TXN-REFUND-001", 10000L);
        assertThatThrownBy(() -> order.recordAcceptance("TXN-REFUND-002"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code()));
    }

    /**
     * critical-3 重复成功回调幂等命中（B8.5 重复回调只生效一次）：已退款
     * 再收同号成功回调 → refund_status_illegal——编排捕获后按已处理应答，
     * 不重放订单/库存编排。
     */
    @Test
    @DisplayName("重复成功回调：已退款再同号回调 → refund_status_illegal（幂等命中）")
    void markSucceeded_duplicateRejected() {
        final RefundOrder order = refundOrder();
        order.recordAcceptance("TXN-REFUND-001");
        order.markSucceeded("TXN-REFUND-001", 10000L);
        assertThatThrownBy(() -> order.markSucceeded("TXN-REFUND-001", 10000L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code()));
    }

    /**
     * critical-4 重复失败回调幂等命中：已失败再收失败回调 →
     * refund_status_illegal（编排捕获按已处理应答，不重放）。
     */
    @Test
    @DisplayName("重复失败回调：已失败再失败回调 → refund_status_illegal（幂等命中）")
    void markRefundFailed_duplicateRejected() {
        final RefundOrder order = refundOrder();
        order.markRefundFailed();
        assertThatThrownBy(order::markRefundFailed)
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code()));
    }

    /* ================= fail path ================= */

    /**
     * fail-1 异号流水冲突（渠道事故优先诊断）：退款单流水已落位且回调
     * 异号 → callback_duplicate(409)（优先于状态守卫诊断，判定顺序见类
     * javadoc）。
     */
    @Test
    @DisplayName("异号流水冲突：callback_duplicate(409) 渠道事故优先")
    void markSucceeded_txnMismatchConflict() {
        final RefundOrder order = refundOrder();
        order.recordAcceptance("TXN-REFUND-001");
        assertThatThrownBy(() -> order.markSucceeded("TXN-REFUND-OTHER", 10000L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.PAYMENT_CALLBACK_DUPLICATE.code());
                    assertThat(error.getHttpStatus()).isEqualTo(409);
                });
    }

    /**
     * fail-2 金额不符：回调金额 ≠ 退款单金额 → amount_mismatch 拒绝
     * （B8.5 ③ 退款金额 = 实付金额——半额/超额不入账，渠道事故显式拒绝）。
     */
    @Test
    @DisplayName("金额不符：回调金额 ≠ 退款单金额 → amount_mismatch 拒绝")
    void markSucceeded_amountMismatchRejected() {
        final RefundOrder order = refundOrder();
        order.recordAcceptance("TXN-REFUND-001");
        assertThatThrownBy(() -> order.markSucceeded("TXN-REFUND-001", 9999L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_AMOUNT_MISMATCH.code()));
        assertThat(order.getStatus()).isEqualTo(RefundOrderStatus.PENDING);
    }

    /**
     * fail-3 留痕 null 拒绝：回调留痕记录必填（防线三素材形态防御）。
     */
    @Test
    @DisplayName("留痕 null 拒绝：gateway_callback_invalid")
    void appendCallbackRecord_nullRejected() {
        final RefundOrder order = refundOrder();
        assertThatThrownBy(() -> order.appendCallbackRecord(null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code()));
    }

    /**
     * fail-4 空白流水受理拒绝：渠道退款流水号必填非空（受理成功必落位）。
     */
    @Test
    @DisplayName("空白流水受理拒绝：渠道流水号必填")
    void recordAcceptance_blankTxnRejected() {
        final RefundOrder order = refundOrder();
        assertThatThrownBy(() -> order.recordAcceptance("  "))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code()));
    }

    /**
     * fail-5 空白流水成功回调拒绝：gateway_callback_invalid（流水是留痕
     * 与一致防线的素材，缺失即渠道事故形态）。
     */
    @Test
    @DisplayName("空白流水成功回调拒绝：gateway_callback_invalid")
    void markSucceeded_blankTxnRejected() {
        final RefundOrder order = refundOrder();
        assertThatThrownBy(() -> order.markSucceeded("", 10000L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code()));
    }
}