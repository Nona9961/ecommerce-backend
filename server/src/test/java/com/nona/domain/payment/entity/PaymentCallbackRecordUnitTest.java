package com.nona.domain.payment.entity;

import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回调留痕记录形态测试（防线三：payment_callback_log 从表行的
 * 领域契约）。
 * <p>
 * 覆盖：回调原文全字段留痕定型（happy）、append-only 不可变（无 setter/
 * 变更路径）、形态守卫位（构造路径必填字段与配套约束，守卫已实现）。
 */
class PaymentCallbackRecordUnitTest {

    /**
     * PAY 成功回调留痕基线（标准化六字段 + 归属 + 收到时间）。
     */
    private final PaymentCallbackRecord record = new PaymentCallbackRecord(
            11L, 1L, CallbackType.PAY, "PAY202609070001", null,
            GatewayResult.SUCCESS, "TXN-ALIPAY-001", 10000L,
            Instant.parse("2026-09-07T10:01:00Z"));

    @Test
    @DisplayName("happy-1 回调原文全字段留痕定型：六字段 + 归属 rootId + 收到时间原样保存")
    void record_carriesFullCallbackPayload() {
        assertThat(record.getId()).isEqualTo(11L);
        assertThat(record.getPaymentOrderId()).isEqualTo(1L);
        assertThat(record.getCallbackType()).isEqualTo(CallbackType.PAY);
        assertThat(record.getPayNo()).isEqualTo("PAY202609070001");
        assertThat(record.getRefundNo()).isNull();
        assertThat(record.getResult()).isEqualTo(GatewayResult.SUCCESS);
        assertThat(record.getChannelTxnNo()).isEqualTo("TXN-ALIPAY-001");
        assertThat(record.getAmountCents()).isEqualTo(10000L);
        assertThat(record.getOccurredAt()).isEqualTo(Instant.parse("2026-09-07T10:01:00Z"));
    }

    @Test
    @DisplayName("happy-2 REFUND 回调留痕形态：退款单号随行保存（退款流接续消费）")
    void record_refundCallbackKeepsRefundNo() {
        final PaymentCallbackRecord refundRecord = new PaymentCallbackRecord(
                12L, 1L, CallbackType.REFUND, "PAY202609070001", "RE202609070001",
                GatewayResult.SUCCESS, "TXN-REFUND-001", 10000L,
                Instant.parse("2026-09-07T10:02:00Z"));
        assertThat(refundRecord.getCallbackType()).isEqualTo(CallbackType.REFUND);
        assertThat(refundRecord.getRefundNo()).isEqualTo("RE202609070001");
    }

    @Test
    @DisplayName("happy-3 失败回调同样留痕：对账不依赖迁移成败")
    void record_failCallbackKeptForReconciliation() {
        final PaymentCallbackRecord failRecord = new PaymentCallbackRecord(
                13L, 1L, CallbackType.PAY, "PAY202609070001", null,
                GatewayResult.FAIL, "TXN-ALIPAY-002", 10000L,
                Instant.parse("2026-09-07T10:03:00Z"));
        assertThat(failRecord.getResult()).isEqualTo(GatewayResult.FAIL);
        assertThat(failRecord.getChannelTxnNo()).isEqualTo("TXN-ALIPAY-002");
    }

    @Test
    @DisplayName("fail-1 形态守卫：归属缺失拒绝（支付单 rootId 必填）")
    void record_missingPaymentOrderId_rejected() {
        assertThatThrownBy(() -> new PaymentCallbackRecord(
                14L, null, CallbackType.PAY, "PAY202609070001", null,
                GatewayResult.SUCCESS, "TXN-ALIPAY-001", 10000L,
                Instant.parse("2026-09-07T10:01:00Z")))
                .isInstanceOf(com.nona.exceptions.BusinessException.class);
    }

    @Test
    @DisplayName("fail-2 形态守卫：渠道流水号空白拒绝（留痕行缺关键字段无对账意义）")
    void record_blankChannelTxnNo_rejected() {
        assertThatThrownBy(() -> new PaymentCallbackRecord(
                15L, 1L, CallbackType.PAY, "PAY202609070001", null,
                GatewayResult.SUCCESS, "", 10000L,
                Instant.parse("2026-09-07T10:01:00Z")))
                .isInstanceOf(com.nona.exceptions.BusinessException.class);
    }

    @Test
    @DisplayName("fail-3 形态守卫：回调金额非正拒绝（渠道金额已校验正数，留痕原样保存）")
    void record_nonPositiveAmount_rejected() {
        assertThatThrownBy(() -> new PaymentCallbackRecord(
                16L, 1L, CallbackType.PAY, "PAY202609070001", null,
                GatewayResult.SUCCESS, "TXN-ALIPAY-001", 0L,
                Instant.parse("2026-09-07T10:01:00Z")))
                .isInstanceOf(com.nona.exceptions.BusinessException.class);
    }

    @Test
    @DisplayName("append-only 钉：无任何 setter/变更方法（反射钉死，红线非空设计的不可变承载）")
    void record_hasNoSetter_appendOnly() {
        assertThat(PaymentCallbackRecord.class.getMethods())
                .filteredOn(m -> m.getName().startsWith("set"))
                .isEmpty();
        assertThat(PaymentCallbackRecord.class.getDeclaredMethods())
                .filteredOn(m -> m.getName().startsWith("set"))
                .isEmpty();
    }
}