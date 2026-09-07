package com.nona.domain.payment.service;

import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.domain.payment.ports.PendingPayment;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentPort 实现侧场景测试（创建/复用 + 关单，红阶段）。
 * <p>
 * 覆盖：happy——补建支付单返回视图（payNo/金额/超时回显）、复用待支付
 * 单（一次支付请求一张支付单）、关单（待支付/失败收口、已关闭幂等）；
 * critical——已存在非待支付单拒绝复用（一对一生义，防重复发起）；
 * fail——支付单不存在 404、已支付关单拒绝。红阶段失败原因 = 实现缺失
 * （方法体 UOE），绿阶段按 javadoc 契约实现后按本矩阵转绿。
 */
@ExtendWith(MockitoExtension.class)
class PaymentPortImplTest {

    @Mock
    private PaymentOrderRepository repository;

    /**
     * 被测端口实现（直接装配，红阶段不注册 Spring；mock 注入后于实例构造，
     * 端口经 setUp 装配）。
     */
    private PaymentPortImpl port;

    /**
     * 每用例前重建被测端口（依赖为 mock，无状态跨用例残留）。
     */
    @BeforeEach
    void setUp() {
        port = new PaymentPortImpl(repository);
    }

    /**
     * 待支付支付单基线（创建构造器装配）。
     */
    private final PaymentOrder pending = new PaymentOrder(
            1L, "PAY202609070001", 100L, 10000L, "MOCK",
            Instant.parse("2026-09-07T10:30:00Z"));

    @Test
    @DisplayName("happy-1 补建：主单无支付单 → 创建待支付单并返回视图（payNo/金额/超时回显）")
    void createPendingPayment_noExisting_createsAndReturnsView() {
        when(repository.findByOrderId(100L)).thenReturn(null);
        final Instant before = Instant.now();
        final PendingPayment created = port.createPendingPayment(100L, 10000L, 1_800_000L);
        final Instant after = Instant.now();
        assertThat(created.paymentOrderId()).isNotNull();
        assertThat(created.payNo()).isNotBlank();
        assertThat(created.amount()).isEqualTo(10000L);
        assertThat(created.payTimeoutMillis()).isEqualTo(1_800_000L);
        assertThat(created.timeoutAt().toEpochMilli())
                .isBetween(before.plusMillis(1_800_000L).toEpochMilli(),
                        after.plusMillis(1_800_000L).toEpochMilli());
        verify(repository).save(org.mockito.ArgumentMatchers.any(PaymentOrder.class));
    }

    @Test
    @DisplayName("happy-2 复用：同主单已存在待支付单 → 复用返回既有视图（不重复创建，B8.1 幂等发起）")
    void createPendingPayment_existingPending_reuses() {
        when(repository.findByOrderId(100L)).thenReturn(pending);
        final PendingPayment reused = port.createPendingPayment(100L, 10000L, 1_800_000L);
        assertThat(reused.paymentOrderId()).isEqualTo(1L);
        assertThat(reused.payNo()).isEqualTo("PAY202609070001");
        assertThat(reused.amount()).isEqualTo(10000L);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(PaymentOrder.class));
    }

    @Test
    @DisplayName("critical-1 复用拒绝：同主单已存在非待支付单（已支付/已失败/已关闭）→ payment.order_invalid")
    void createPendingPayment_existingNonPending_rejected() {
        final PaymentOrder paid = new PaymentOrder(2L, "PAY202609070002", 100L, 10000L,
                "MOCK", Instant.parse("2026-09-07T10:30:00Z"),
                PaymentOrderStatus.PAID, "TXN-ALIPAY-001", java.util.List.of());
        when(repository.findByOrderId(100L)).thenReturn(paid);
        assertThatThrownBy(() -> port.createPendingPayment(100L, 10000L, 1_800_000L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_ORDER_INVALID.code());
    }

    @Test
    @DisplayName("happy-3 关单：待支付单 → 关闭并落库（同事务编排：order.cancel → 库存回滚 → closePay）")
    void closePay_pending_closesAndSaves() {
        when(repository.findByPayNo("PAY202609070001")).thenReturn(pending);
        port.closePay("PAY202609070001");
        assertThat(pending.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED);
        verify(repository).save(pending);
    }

    @Test
    @DisplayName("happy-4 关单幂等：已关闭单重复关单成功返回（超时调度与主动取消重放无害）")
    void closePay_closed_idempotent() {
        final PaymentOrder closed = new PaymentOrder(3L, "PAY202609070003", 101L, 20000L,
                "MOCK", Instant.parse("2026-09-07T10:30:00Z"),
                PaymentOrderStatus.CLOSED, null, java.util.List.of());
        when(repository.findByPayNo("PAY202609070003")).thenReturn(closed);
        port.closePay("PAY202609070003");
        assertThat(closed.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED);
    }

    @Test
    @DisplayName("happy-5 关单收口：失败单经超时编排关单 → CLOSED（不仅靠超时，失败可显式收口）")
    void closePay_failed_closes() {
        final PaymentOrder failed = new PaymentOrder(4L, "PAY202609070004", 102L, 30000L,
                "MOCK", Instant.parse("2026-09-07T10:30:00Z"),
                PaymentOrderStatus.FAILED, "TXN-ALIPAY-004", java.util.List.of());
        when(repository.findByPayNo("PAY202609070004")).thenReturn(failed);
        port.closePay("PAY202609070004");
        assertThat(failed.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED);
    }

    @Test
    @DisplayName("fail-1 关单：支付单不存在 → payment.not_found（404）")
    void closePay_notFound_rejected() {
        when(repository.findByPayNo("PAY202609070999")).thenReturn(null);
        assertThatThrownBy(() -> port.closePay("PAY202609070999"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_NOT_FOUND.code());
    }

    @Test
    @DisplayName("fail-2 关单：已支付 → 拒绝（资金锁定，关单走退款流：payment.status_illegal）")
    void closePay_paid_rejected() {
        final PaymentOrder paid = new PaymentOrder(2L, "PAY202609070002", 100L, 10000L,
                "MOCK", Instant.parse("2026-09-07T10:30:00Z"),
                PaymentOrderStatus.PAID, "TXN-ALIPAY-001", java.util.List.of());
        when(repository.findByPayNo("PAY202609070002")).thenReturn(paid);
        assertThatThrownBy(() -> port.closePay("PAY202609070002"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_STATUS_ILLEGAL.code());
    }
}