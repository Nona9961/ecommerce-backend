package com.nona.inf.payment;

import com.nona.domain.payment.ports.AcquireRequest;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayCallbackPayload;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.domain.payment.ports.RefundRequest;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mock 支付渠道错误路径测试（渠道自身参数防御）。
 * <p>
 * 覆盖：error——受理/退款/查询的单号空白与金额非正拒绝（网关参数非法
 * 业务码）、回调缺字段/金额非正/结果缺失/类型与字段不配套拒绝（回调
 * 非法业务码）。幂等（重复受理/重复回调）与状态一致性防线归业务侧回调
 * 编排，不属渠道自身语义。
 */
class MockPaymentGatewayErrorPathUnitTest {

    /**
     * 被测渠道。
     */
    private final MockPaymentGateway gateway = new MockPaymentGateway("SUCCESS");

    /**
     * error：受理请求整体缺失/单号空白拒绝（网关参数非法）。
     */
    @Test
    @DisplayName("受理请求缺失或单号空白拒绝")
    void acquire_nullRequestOrBlankPayNo_rejected() {
        assertThatThrownBy(() -> gateway.acquire(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("payment.gateway_invalid_argument"));
        assertThatThrownBy(() -> gateway.acquire(new AcquireRequest(null, 100L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gateway.acquire(new AcquireRequest("  ", 100L)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：受理金额非正拒绝（0 与负数）。
     */
    @Test
    @DisplayName("受理金额0或负数拒绝")
    void acquire_nonPositiveAmount_rejected() {
        assertThatThrownBy(() -> gateway.acquire(new AcquireRequest("PAY2026090501#SUCCESS", 0L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gateway.acquire(new AcquireRequest("PAY2026090501#SUCCESS", -1L)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：回调载荷缺失/单号空白拒绝（回调非法，与受理参数非法区分）。
     */
    @Test
    @DisplayName("回调载荷缺失或单号空白拒绝")
    void callback_nullPayloadOrBlankPayNo_rejected() {
        assertThatThrownBy(() -> gateway.handleCallback(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("payment.gateway_callback_invalid"));
        assertThatThrownBy(() -> gateway.handleCallback(
                new GatewayCallbackPayload(CallbackType.PAY, "", null, GatewayResult.SUCCESS,
                        "txn-1", 100L)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：回调缺渠道流水号拒绝（流水号是业务侧唯一约束防线的素材，
     * 渠道强校验）。
     */
    @Test
    @DisplayName("回调缺渠道流水号拒绝")
    void callback_missingChannelTxnNo_rejected() {
        assertThatThrownBy(() -> gateway.handleCallback(
                new GatewayCallbackPayload(CallbackType.PAY, "PAY2026090501#SUCCESS", null,
                        GatewayResult.SUCCESS, null, 100L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gateway.handleCallback(
                new GatewayCallbackPayload(CallbackType.PAY, "PAY2026090501#SUCCESS", null,
                        GatewayResult.SUCCESS, "  ", 100L)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：回调金额非正拒绝（0 与负数——渠道侧金额防御，金额一致性核
     * 对归业务侧聚合）。
     */
    @Test
    @DisplayName("回调金额0或负数拒绝")
    void callback_nonPositiveAmount_rejected() {
        assertThatThrownBy(() -> gateway.handleCallback(
                new GatewayCallbackPayload(CallbackType.PAY, "PAY2026090501#SUCCESS", null,
                        GatewayResult.SUCCESS, "txn-1", 0L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gateway.handleCallback(
                new GatewayCallbackPayload(CallbackType.PAY, "PAY2026090501#SUCCESS", null,
                        GatewayResult.SUCCESS, "txn-1", -100L)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：回调结果缺失拒绝（结果为空即无法判定业务语义）。
     */
    @Test
    @DisplayName("回调结果缺失拒绝")
    void callback_nullResult_rejected() {
        assertThatThrownBy(() -> gateway.handleCallback(
                new GatewayCallbackPayload(CallbackType.PAY, "PAY2026090501#SUCCESS", null,
                        null, "txn-1", 100L)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：退款回调缺退款单号拒绝（REFUND 类型必须携带退款单号——
     * 业务侧按此定位退款单）。
     */
    @Test
    @DisplayName("退款回调缺退款单号拒绝")
    void callback_refundTypeWithoutRefundNo_rejected() {
        assertThatThrownBy(() -> gateway.handleCallback(
                new GatewayCallbackPayload(CallbackType.REFUND, "PAY2026090501#SUCCESS", null,
                        GatewayResult.SUCCESS, "txn-1", 100L)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：支付回调携带退款单号拒绝（类型与字段不配套的防御——payload
     * 形态必须严格）。
     */
    @Test
    @DisplayName("支付回调携带退款单号拒绝")
    void callback_payTypeWithRefundNo_rejected() {
        assertThatThrownBy(() -> gateway.handleCallback(
                new GatewayCallbackPayload(CallbackType.PAY, "PAY2026090501#SUCCESS",
                        "RE2026090501", GatewayResult.SUCCESS, "txn-1", 100L)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：退款受理单号空白拒绝（支付单号/退款单号任一空白）。
     */
    @Test
    @DisplayName("退款受理单号空白拒绝")
    void refund_blankPayNoOrRefundNo_rejected() {
        assertThatThrownBy(() -> gateway.refund(new RefundRequest(" ", "RE2026090501", 100L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gateway.refund(new RefundRequest("PAY2026090501#SUCCESS", null, 100L)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：退款金额非正拒绝。
     */
    @Test
    @DisplayName("退款金额0或负数拒绝")
    void refund_nonPositiveAmount_rejected() {
        assertThatThrownBy(() -> gateway.refund(new RefundRequest("PAY2026090501#SUCCESS",
                "RE2026090501", 0L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gateway.refund(new RefundRequest("PAY2026090501#SUCCESS",
                "RE2026090501", -50L)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：状态查询单号空白拒绝。
     */
    @Test
    @DisplayName("状态查询单号空白拒绝")
    void query_blankPayNo_rejected() {
        assertThatThrownBy(() -> gateway.query(null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gateway.query("  "))
                .isInstanceOf(BusinessException.class);
    }
}