package com.nona.inf.payment;

import com.nona.domain.payment.ports.AcquireRequest;
import com.nona.domain.payment.ports.AcquireResult;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayCallbackPayload;
import com.nona.domain.payment.ports.GatewayPaymentStatus;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.domain.payment.ports.RefundRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mock 支付渠道临界/边界测试（剧本路由边界、金额边界与透传、query 一致性）。
 * <p>
 * 覆盖：critical——无标记单号走默认剧本、默认剧本可配置切换（失败/静默）
 * 且非法配置回落成功、标记位置与大小写边界（仅尾段、仅大写命中）、最小
 * 正金额与超大金额受理、回调金额透传不经改写、query 恒定性（反复查询/
 * 受理前查询同结果）。
 */
class MockPaymentGatewayCriticalPathTest {

    /**
     * critical：无标记单号命中默认剧本（默认 SUCCESS）——业务常规单号
     * （不带模拟标记）稳定走成功路径。
     */
    @Test
    @DisplayName("无标记单号走默认成功剧本")
    void routing_payNoWithoutMark_usesDefaultSuccess() {
        final MockPaymentGateway gateway = new MockPaymentGateway("SUCCESS");
        assertThat(gateway.query("PAY202609059999")).isEqualTo(GatewayPaymentStatus.SUCCESS);
    }

    /**
     * critical：默认剧本可配置切换——整体演示「失败场景/超时场景」只改
     * 配置，无标记单号即走对应剧本。
     */
    @Test
    @DisplayName("默认剧本配置切换为失败与静默")
    void routing_defaultScriptConfigurable() {
        assertThat(new MockPaymentGateway("FAIL").query("PAY202609059999"))
                .isEqualTo(GatewayPaymentStatus.FAIL);
        assertThat(new MockPaymentGateway("NOCALLBACK").query("PAY202609059999"))
                .isEqualTo(GatewayPaymentStatus.WAIT_PAY);
    }

    /**
     * critical：非法默认剧本配置回落 SUCCESS（fail-safe，不因配置笔误
     * 整链路不可用）。
     */
    @Test
    @DisplayName("非法默认剧本配置回落成功剧本")
    void routing_unknownDefaultScript_fallsBackToSuccess() {
        assertThat(new MockPaymentGateway("BOGUS").query("PAY202609059999"))
                .isEqualTo(GatewayPaymentStatus.SUCCESS);
    }

    /**
     * critical：标记必须位于 payNo 末尾——出现在中间不被识别（回落默认
     * 剧本），避免业务单号中段自然出现的字符被误判。
     */
    @Test
    @DisplayName("标记位于单号中间不命中剧本路由")
    void routing_markInMiddle_notMatched() {
        final MockPaymentGateway gateway = new MockPaymentGateway("SUCCESS");
        assertThat(gateway.query("PAY#FAIL20260905")).isEqualTo(GatewayPaymentStatus.SUCCESS);
        assertThat(gateway.query("PAY2026#FAIL0905")).isEqualTo(GatewayPaymentStatus.SUCCESS);
    }

    /**
     * critical：剧本标记大小写敏感——小写变体不命中（回落默认剧本），
     * 路由规则可预测。
     */
    @Test
    @DisplayName("小写标记不命中剧本路由")
    void routing_lowerCaseMark_notMatched() {
        final MockPaymentGateway gateway = new MockPaymentGateway("SUCCESS");
        assertThat(gateway.query("PAY2026090504#fail")).isEqualTo(GatewayPaymentStatus.SUCCESS);
        assertThat(gateway.query("PAY2026090504#nocallback")).isEqualTo(GatewayPaymentStatus.SUCCESS);
    }

    /**
     * critical：最小正金额（1 分）受理通过且回调校验通过——正金额边界
     * 不误拒。
     */
    @Test
    @DisplayName("最小正金额1分受理与回调通过")
    void amount_oneCent_acceptedAndCallbackPassed() {
        final AcquireResult accepted = new MockPaymentGateway("SUCCESS").acquire(
                new AcquireRequest("PAY2026090505#SUCCESS", 1L));
        assertThat(accepted.accepted()).isTrue();
        assertThat(new MockPaymentGateway("SUCCESS").handleCallback(
                new GatewayCallbackPayload(CallbackType.PAY, "PAY2026090505#SUCCESS", null,
                        GatewayResult.SUCCESS, accepted.channelTxnNo(), 1L)).amountCents())
                .isEqualTo(1L);
    }

    /**
     * critical：超大金额（10 亿元分，Long 正数域内）受理通过——金额透传
     * 无溢出改写。
     */
    @Test
    @DisplayName("超大金额受理通过")
    void amount_hugeAmount_accepted() {
        final AcquireResult accepted = new MockPaymentGateway("SUCCESS").acquire(
                new AcquireRequest("PAY2026090506#SUCCESS", 10_000_000_000L));
        assertThat(accepted.accepted()).isTrue();
    }

    /**
     * critical：金额参数透传——回调金额原样返回，mock 不经任何改写
     * （受理金额与回调金额一致性的业务核对归业务侧聚合）。
     */
    @Test
    @DisplayName("回调金额原样透传")
    void callback_amountTransparency_passedThrough() {
        final AcquireResult accepted = new MockPaymentGateway("SUCCESS").acquire(
                new AcquireRequest("PAY2026090507#SUCCESS", 88888L));
        final GatewayResult result = new MockPaymentGateway("SUCCESS").handleCallback(
                new GatewayCallbackPayload(CallbackType.PAY, "PAY2026090507#SUCCESS", null,
                        GatewayResult.SUCCESS, accepted.channelTxnNo(), 88888L)).result();
        assertThat(result).isEqualTo(GatewayResult.SUCCESS);
    }

    /**
     * critical：query 恒定性——同一单号多次查询结果稳定；未受理直接查询
     * 与受理后查询结果一致（无状态规则模型，渠道侧视角不依赖受理记录）。
     */
    @Test
    @DisplayName("query 反复查询与受理前后结果一致")
    void query_stableAndIndependentOfAcquire() {
        final MockPaymentGateway gateway = new MockPaymentGateway("SUCCESS");
        assertThat(gateway.query("PAY2026090508#FAIL")).isEqualTo(GatewayPaymentStatus.FAIL);
        gateway.acquire(new AcquireRequest("PAY2026090508#FAIL", 5000L));
        assertThat(gateway.query("PAY2026090508#FAIL")).isEqualTo(GatewayPaymentStatus.FAIL);
        assertThat(gateway.query("PAY2026090508#FAIL")).isEqualTo(GatewayPaymentStatus.FAIL);
    }

    /**
     * critical：退款最小正金额受理通过（退款金额边界不误拒）。
     */
    @Test
    @DisplayName("退款最小正金额1分受理通过")
    void refund_oneCent_accepted() {
        final var accepted = new MockPaymentGateway("SUCCESS").refund(
                new RefundRequest("PAY2026090501#SUCCESS", "RE2026090502", 1L));
        assertThat(accepted.accepted()).isTrue();
    }
}