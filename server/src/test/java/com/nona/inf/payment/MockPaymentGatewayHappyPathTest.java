package com.nona.inf.payment;

import com.nona.domain.payment.ports.AcquireRequest;
import com.nona.domain.payment.ports.AcquireResult;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayCallbackPayload;
import com.nona.domain.payment.ports.GatewayPaymentStatus;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.domain.payment.ports.RefundRequest;
import com.nona.domain.payment.ports.RefundResult;
import com.nona.domain.payment.ports.ValidatedCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mock 支付渠道主流程测试（三剧本 × 受理/回调/退款/查询语义）。
 * <p>
 * 覆盖：happy——三剧本（成功/失败/不回调）受理均成功（受理 ≠ 支付结果）、
 * 受理结果三要素（payNo 回显/渠道流水/收银台标识）、SUCCESS 与 FAIL 回调
 * 校验通过并原样透传、退款受理与 REFUND 回调、query 按剧本的渠道侧状态
 * 语义。
 */
class MockPaymentGatewayHappyPathTest {

    /**
     * 被测渠道（默认剧本 SUCCESS，无标记单号走成功剧本）。
     */
    private final MockPaymentGateway gateway = new MockPaymentGateway("SUCCESS");

    /**
     * happy：SUCCESS 剧本受理成功——受理结果三要素齐备。
     */
    @Test
    @DisplayName("成功剧本受理成功且返回流水号与收银台标识")
    void acquire_successScript_accepted() {
        final AcquireResult result = gateway.acquire(
                new AcquireRequest("PAY2026090501#SUCCESS", 10000L));
        assertThat(result.accepted()).isTrue();
        assertThat(result.payNo()).isEqualTo("PAY2026090501#SUCCESS");
        assertThat(result.channelTxnNo()).isNotBlank();
        assertThat(result.cashierToken()).isNotBlank();
    }

    /**
     * happy：FAIL 剧本受理同样成功——受理成功不代表支付成功，失败语义在
     * 回调与 query（受理与结果分离的异步回调模型核心）。
     */
    @Test
    @DisplayName("失败剧本受理成功（受理与支付结果分离）")
    void acquire_failScript_accepted() {
        final AcquireResult result = gateway.acquire(
                new AcquireRequest("PAY2026090502#FAIL", 20000L));
        assertThat(result.accepted()).isTrue();
        assertThat(result.channelTxnNo()).isNotBlank();
    }

    /**
     * happy：NOCALLBACK 剧本受理成功——永不回调，触发业务侧超时路径；
     * 受理侧与其余剧本无差异。
     */
    @Test
    @DisplayName("不回调剧本受理成功")
    void acquire_noCallbackScript_accepted() {
        final AcquireResult result = gateway.acquire(
                new AcquireRequest("PAY2026090503#NOCALLBACK", 30000L));
        assertThat(result.accepted()).isTrue();
        assertThat(result.channelTxnNo()).isNotBlank();
    }

    /**
     * happy：SUCCESS 回调校验通过——回调流水与受理流水一致、金额透传，
     * 返回标准化回调事件（type/result/金额原样，mock 不做改写）。
     */
    @Test
    @DisplayName("支付成功回调校验通过并回传受理流水")
    void callback_paySuccess_passedWithSameChannelTxn() {
        final AcquireResult accepted = gateway.acquire(
                new AcquireRequest("PAY2026090501#SUCCESS", 10000L));
        final ValidatedCallback callback = gateway.handleCallback(
                new GatewayCallbackPayload(CallbackType.PAY, "PAY2026090501#SUCCESS", null,
                        GatewayResult.SUCCESS, accepted.channelTxnNo(), 10000L));
        assertThat(callback.type()).isEqualTo(CallbackType.PAY);
        assertThat(callback.payNo()).isEqualTo("PAY2026090501#SUCCESS");
        assertThat(callback.refundNo()).isNull();
        assertThat(callback.result()).isEqualTo(GatewayResult.SUCCESS);
        assertThat(callback.channelTxnNo()).isEqualTo(accepted.channelTxnNo());
        assertThat(callback.amountCents()).isEqualTo(10000L);
    }

    /**
     * happy：支付失败回调（FAIL 剧本单）校验通过——失败结果同样以回调
     * 形态异步到达。
     */
    @Test
    @DisplayName("支付失败回调校验通过")
    void callback_payFail_passed() {
        final AcquireResult accepted = gateway.acquire(
                new AcquireRequest("PAY2026090502#FAIL", 20000L));
        final ValidatedCallback callback = gateway.handleCallback(
                new GatewayCallbackPayload(CallbackType.PAY, "PAY2026090502#FAIL", null,
                        GatewayResult.FAIL, accepted.channelTxnNo(), 20000L));
        assertThat(callback.result()).isEqualTo(GatewayResult.FAIL);
        assertThat(callback.amountCents()).isEqualTo(20000L);
    }

    /**
     * happy：退款受理成功——payNo/refundNo 回显、渠道退款流水生成。
     */
    @Test
    @DisplayName("退款受理成功且返回退款流水号")
    void refund_accepted() {
        final RefundResult result = gateway.refund(
                new RefundRequest("PAY2026090501#SUCCESS", "RE2026090501", 10000L));
        assertThat(result.accepted()).isTrue();
        assertThat(result.payNo()).isEqualTo("PAY2026090501#SUCCESS");
        assertThat(result.refundNo()).isEqualTo("RE2026090501");
        assertThat(result.channelRefundTxnNo()).isNotBlank();
    }

    /**
     * happy：REFUND 成功回调校验通过——退款结果以 REFUND 回调形态异步
     * 到达（与支付同构；channelTxnNo 承载退款流水）。
     */
    @Test
    @DisplayName("退款成功回调校验通过")
    void callback_refundSuccess_passed() {
        final RefundResult accepted = gateway.refund(
                new RefundRequest("PAY2026090501#SUCCESS", "RE2026090501", 10000L));
        final ValidatedCallback callback = gateway.handleCallback(
                new GatewayCallbackPayload(CallbackType.REFUND, "PAY2026090501#SUCCESS",
                        "RE2026090501", GatewayResult.SUCCESS, accepted.channelRefundTxnNo(), 10000L));
        assertThat(callback.type()).isEqualTo(CallbackType.REFUND);
        assertThat(callback.refundNo()).isEqualTo("RE2026090501");
        assertThat(callback.result()).isEqualTo(GatewayResult.SUCCESS);
        assertThat(callback.channelTxnNo()).isEqualTo(accepted.channelRefundTxnNo());
    }

    /**
     * happy：query 按剧本返回渠道侧状态——成功单 SUCCESS / 失败单 FAIL /
     * 静默单 WAIT_PAY（渠道侧视角与业务状态机两套口径）。
     */
    @Test
    @DisplayName("query 三剧本状态语义")
    void query_scriptStatusConsistent() {
        assertThat(gateway.query("PAY2026090501#SUCCESS")).isEqualTo(GatewayPaymentStatus.SUCCESS);
        assertThat(gateway.query("PAY2026090502#FAIL")).isEqualTo(GatewayPaymentStatus.FAIL);
        assertThat(gateway.query("PAY2026090503#NOCALLBACK")).isEqualTo(GatewayPaymentStatus.WAIT_PAY);
    }
}