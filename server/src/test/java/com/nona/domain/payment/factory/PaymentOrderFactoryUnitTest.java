package com.nona.domain.payment.factory;

import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付单工厂场景测试（创建入口契约）：
 * happy——创建返回待支付支付单（字段定型：非空 payNo/待支付/空留痕/
 * 未回调流水/超时截止 = 创建时刻 + 超时时长）；critical——payNo 形态
 * 符合单号规则（PAY + yyyyMMdd + snowflake 后段）且趋势递增；fail——
 * 形态守卫（主单缺失/金额非负/渠道空白/超时时长非法）。
 */
class PaymentOrderFactoryUnitTest {

    /**
     * 被测工厂（普通类，直接装配）。
     */
    private final PaymentOrderFactory factory = new PaymentOrderFactory();

    @Test
    @DisplayName("happy-1 发起支付创建支付单：待支付 + 关联主单 + 金额 + 渠道定型")
    void create_pendingPaymentAssembled() {
        final PaymentOrder order = factory.create(100L, 10000L, "MOCK", 1_800_000L);
        assertThat(order.getOrderId()).isEqualTo(100L);
        assertThat(order.getAmount()).isEqualTo(10000L);
        assertThat(order.getChannel()).isEqualTo("MOCK");
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PENDING_PAYMENT);
        assertThat(order.getPayNo()).isNotBlank();
    }

    @Test
    @DisplayName("happy-2 创建定型：超时截止 = 创建时刻 + 超时时长，留痕集合为空，未回调流水")
    void create_timeoutAtAndEmptyStateAssembled() {
        final long before = System.currentTimeMillis();
        final PaymentOrder order = factory.create(100L, 10000L, "MOCK", 1_800_000L);
        final long after = System.currentTimeMillis();
        assertThat(order.getTimeoutAt().toEpochMilli())
                .isBetween(before + 1_800_000L, after + 1_800_000L);
        assertThat(order.getCallbacks()).isEmpty();
        assertThat(order.getChannelTxnNo()).isNull();
    }

    @Test
    @DisplayName("critical-1 payNo 形态：单号规则（PAY + yyyyMMdd + snowflake 后段），趋势递增")
    void create_payNoFollowsShape() {
        final PaymentOrder first = factory.create(100L, 10000L, "MOCK", 1_800_000L);
        final PaymentOrder second = factory.create(100L, 10000L, "MOCK", 1_800_000L);
        assertThat(first.getPayNo()).startsWith("PAY");
        assertThat(first.getPayNo()).hasSizeGreaterThan(3 + 8);
        assertThat(first.getPayNo().substring(3, 11)).matches("\\d{8}");
        assertThat(second.getPayNo()).isGreaterThan(first.getPayNo());
    }

    @Test
    @DisplayName("fail-1 形态守卫：主单缺失拒绝（支付单必挂主单，一对一锚点）")
    void create_nullOrderId_rejected() {
        assertThatThrownBy(() -> factory.create(null, 10000L, "MOCK", 1_800_000L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("fail-2 形态守卫：金额非负拒绝（实付为负无业务意义）")
    void create_negativeAmount_rejected() {
        assertThatThrownBy(() -> factory.create(100L, -1L, "MOCK", 1_800_000L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("fail-3 形态守卫：渠道空白拒绝（无渠道的支付单无法受理）")
    void create_blankChannel_rejected() {
        assertThatThrownBy(() -> factory.create(100L, 10000L, " ", 1_800_000L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("fail-4 形态守卫：超时时长非法拒绝（非正超时注册无意义）")
    void create_invalidTimeout_rejected() {
        assertThatThrownBy(() -> factory.create(100L, 10000L, "MOCK", -1L))
                .isInstanceOf(BusinessException.class);
    }
}