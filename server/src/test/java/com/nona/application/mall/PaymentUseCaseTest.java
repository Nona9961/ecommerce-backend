package com.nona.application.mall;

import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.payment.ports.AcquireRequest;
import com.nona.domain.payment.ports.AcquireResult;
import com.nona.domain.payment.ports.PaymentGateway;
import com.nona.domain.payment.ports.PaymentPort;
import com.nona.domain.payment.ports.PendingPayment;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发起支付用例场景测试（B8.1 + C1 历史遗留主单状态防线，红阶段）。
 * <p>
 * 覆盖：happy——复用待支付单受理（B8.1 主流程）、补建支付单受理（金额
 * = 主单实付，超时长 = 订单域规则值）；critical——受理失败剧本透传；
 * fail——主单不存在/归属不符（404 按不存在呈现，防越权泄露）、主单不可
 * 支付再发起（C1：payment.order_invalid）、已存在非待支付单拒绝复用。
 * 红阶段失败原因 = 实现缺失（用例方法体 UOE），绿阶段实现后按本矩阵
 * 转绿。
 */
@ExtendWith(MockitoExtension.class)
class PaymentUseCaseTest {

    @Mock
    private PaymentPort paymentPort;

    @Mock
    private PaymentGateway gateway;

    @Mock
    private MasterOrderRepository masterOrderRepository;

    /**
     * 被测用例（直接装配，红阶段不注册 Spring；mock 注入后于实例构造，
     * 用例经 setUp 装配）。
     */
    private PaymentUseCase useCase;

    /**
     * 每用例前重建被测用例（依赖均为 mock，无状态跨用例残留）。
     */
    @BeforeEach
    void setUp() {
        useCase = new PaymentUseCase(paymentPort, gateway, masterOrderRepository);
    }

    /**
     * 待支付主单基线（买家 200，订单 100，实付 10000 分）。
     */
    private final MasterOrder pendingMaster = master(100L, MasterOrderStatus.PENDING_PAYMENT);

    /**
     * 按状态装配合法主单（地址/金额快照合法定型——主单已实现构造守卫）。
     */
    private static MasterOrder master(Long id, MasterOrderStatus status) {
        return new MasterOrder(id, "ORD202609070001", 200L,
                new AddressSnapshot("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号"),
                new AmountDetail(10000L, 0L, 0L, 10000L), List.of(1000L), null, status);
    }

    /**
     * 待支付支付单视图基线。
     */
    private final PendingPayment pendingPayment = new PendingPayment(
            1L, "PAY202609070001", 10000L, 1_800_000L,
            Instant.parse("2026-09-07T10:30:00Z"));

    @Test
    @DisplayName("happy-1 发起支付主流程：主单可支付 → 复用/创建支付单 → 渠道受理成功（B8.1）")
    void initiatePayment_happyPath_acquired() {
        when(masterOrderRepository.getByID(100L)).thenReturn(pendingMaster);
        when(paymentPort.createPendingPayment(eq(100L), eq(10000L), any(Long.class)))
                .thenReturn(pendingPayment);
        when(gateway.acquire(any(AcquireRequest.class)))
                .thenReturn(new AcquireResult(true, "PAY202609070001", "CHANNEL-TXN-001", "CASHIER-TOKEN"));

        final AcquireResult result = useCase.initiatePayment(200L, 100L);

        assertThat(result.accepted()).isTrue();
        assertThat(result.payNo()).isEqualTo("PAY202609070001");
        assertThat(result.channelTxnNo()).isNotBlank();
        assertThat(result.cashierToken()).isNotBlank();
        verify(gateway).acquire(any(AcquireRequest.class));
    }

    @Test
    @DisplayName("happy-2 支付金额 = 主单实付：创建入参金额来自主单金额快照")
    void initiatePayment_amountFromMasterPaid() {
        when(masterOrderRepository.getByID(100L)).thenReturn(pendingMaster);
        when(paymentPort.createPendingPayment(100L, 10000L, 1_800_000L))
                .thenReturn(pendingPayment);
        when(gateway.acquire(any(AcquireRequest.class)))
                .thenReturn(new AcquireResult(true, "PAY202609070001", "CHANNEL-TXN-001", "TOKEN"));
        useCase.initiatePayment(200L, 100L);
        verify(paymentPort).createPendingPayment(100L, 10000L, 1_800_000L);
    }

    @Test
    @DisplayName("critical-1 受理失败剧本透传：渠道拒绝受理（失败剧本单号）→ 业务异常原样透传")
    void initiatePayment_gatewayRejected_propagated() {
        when(masterOrderRepository.getByID(100L)).thenReturn(pendingMaster);
        when(paymentPort.createPendingPayment(eq(100L), eq(10000L), any(Long.class)))
                .thenReturn(pendingPayment);
        when(gateway.acquire(any(AcquireRequest.class)))
                .thenThrow(new BusinessException(EcommerceBusinessCode.PAYMENT_GATEWAY_INVALID_ARGUMENT.code(),
                        "渠道拒绝受理"));
        assertThatThrownBy(() -> useCase.initiatePayment(200L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_GATEWAY_INVALID_ARGUMENT.code());
    }

    @Test
    @DisplayName("fail-1 主单不存在：404 按不存在呈现（order.master_not_found）")
    void initiatePayment_masterNotFound_rejected() {
        when(masterOrderRepository.getByID(999L)).thenReturn(null);
        assertThatThrownBy(() -> useCase.initiatePayment(200L, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code());
        verify(paymentPort, never()).createPendingPayment(any(Long.class), any(Long.class), any(Long.class));
    }

    @Test
    @DisplayName("fail-2 归属不符：他人主单按不存在呈现（防越权与存在性泄露）")
    void initiatePayment_buyerMismatch_rejected() {
        when(masterOrderRepository.getByID(100L)).thenReturn(pendingMaster);
        assertThatThrownBy(() -> useCase.initiatePayment(999L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code());
        verify(paymentPort, never()).createPendingPayment(any(Long.class), any(Long.class), any(Long.class));
    }

    @Test
    @DisplayName("fail-3 C1 主单状态防线：已支付/已取消等非可支付主单再发起 → payment.order_invalid")
    void initiatePayment_masterNotPayable_rejected() {
        final MasterOrder paidMaster = master(100L, MasterOrderStatus.PAID);
        when(masterOrderRepository.getByID(100L)).thenReturn(paidMaster);
        assertThatThrownBy(() -> useCase.initiatePayment(200L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_ORDER_INVALID.code());
        verify(paymentPort, never()).createPendingPayment(any(Long.class), any(Long.class), any(Long.class));
    }

    @Test
    @DisplayName("fail-4 已存在非待支付支付单：端口拒绝透传（payment.order_invalid，防重复发起）")
    void initiatePayment_existingNonPendingPortRejection_propagated() {
        when(masterOrderRepository.getByID(100L)).thenReturn(pendingMaster);
        when(paymentPort.createPendingPayment(eq(100L), eq(10000L), any(Long.class)))
                .thenThrow(new BusinessException(EcommerceBusinessCode.PAYMENT_ORDER_INVALID.code(),
                        "主单已存在已支付支付单"));
        assertThatThrownBy(() -> useCase.initiatePayment(200L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_ORDER_INVALID.code());
        verify(gateway, never()).acquire(any(AcquireRequest.class));
    }
}