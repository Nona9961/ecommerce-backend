package com.nona.inf.persistence.repository;

import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.inf.persistence.converters.PaymentOrderConvertor;
import com.nona.inf.persistence.po.payment.PaymentCallbackLogPO;
import com.nona.inf.persistence.po.payment.PaymentOrderPO;
import com.nona.inf.persistence.repository.jpa.PaymentCallbackLogJpaRepository;
import com.nona.inf.persistence.repository.jpa.PaymentOrderJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付单仓储实现场景测试（WU-55 红阶段契约：回调装载锚点对 / 支付
 * 超时三件套（findDue-claim-clear）/ 级联删）。
 * <p>
 * happy——findByPayNo/findByOrderId 装载命中、findDue 状态与时刻透传、
 * claim 条件命中 1 行 → true、clear 无条件幂等、级联删留痕先删；critical
 * ——findDue 无到期空列表；fail——装载不存在均按 null 呈现（孤儿回调
 * /补建判定语义，接口 javadoc 冻结）、claim 0 行 → false、删除不存在
 * 返回 0。
 * <p>
 * 装配纪律：依赖全 mock（含超时条件更新落地面 JdbcTemplate），无容器；
 * 被测仓储 @BeforeEach 重建。桩纪律同 SubOrder 判例（lenient 豁免 UOE
 * 挡道，绿实现后收回）。时间断言：now 为 fixture 输入，透传断言相对
 * {@link #NOW} 派生（UTC 字面）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class PaymentOrderRepositoryImplUnitTest {

    /**
     * 支付单号（回调装载锚点业务键）
     */
    private static final String PAY_NO = "PAY202609080000000001";

    /**
     * 关联主单 ID（复用判定装载锚点业务键）
     */
    private static final long ORDER_ID = 9001L;

    /**
     * 扫描时刻 fixture（findDue 透传断言基准）
     */
    private static final Instant NOW = Instant.parse("2026-09-08T12:30:00Z");

    /**
     * now 的 UTC 字面（PO LocalDateTime 列承载面）
     */
    private static final LocalDateTime NOW_UTC =
            NOW.atOffset(ZoneOffset.UTC).toLocalDateTime();

    /**
     * 单轮扫描上限
     */
    private static final int LIMIT = 100;

    @Mock
    private PaymentOrderJpaRepository paymentOrderJpaRepository;

    @Mock
    private PaymentOrderConvertor convertor;

    @Mock
    private ChangeTrackerProvider changeTrackerProvider;

    @Mock
    private PaymentCallbackLogJpaRepository callbackLogJpaRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    /**
     * 被测仓储（setUp 重建）
     */
    private PaymentOrderRepositoryImpl repository;

    /**
     * 每用例前重建被测仓储（mock 桩在各用例内布置）。
     */
    @BeforeEach
    void setUp() {
        repository = new PaymentOrderRepositoryImpl(paymentOrderJpaRepository, convertor,
                changeTrackerProvider, callbackLogJpaRepository, jdbcTemplate);
    }

    @Test
    @DisplayName("happy：按支付单号装载命中（回调处理锚点）")
    void findByPayNo_match_returnsOrder() {
        final PaymentOrderPO po = new PaymentOrderPO();
        when(paymentOrderJpaRepository.findByPayNo(PAY_NO)).thenReturn(Optional.of(po));
        final PaymentOrder order = org.mockito.Mockito.mock(PaymentOrder.class);
        when(convertor.convertToRoot(any(PaymentOrderPO.class), any()))
                .thenReturn(order);
        when(callbackLogJpaRepository.findByPaymentOrderIdOrderByIdAsc(any()))
                .thenReturn(List.of());

        final PaymentOrder result = repository.findByPayNo(PAY_NO);

        assertThat(result).isSameAs(order);
    }

    @Test
    @DisplayName("fail：按支付单号装载不存在返回 null（孤儿回调不产生处理路径）")
    void findByPayNo_absent_returnsNull() {
        when(paymentOrderJpaRepository.findByPayNo(PAY_NO)).thenReturn(Optional.empty());

        assertThat(repository.findByPayNo(PAY_NO)).isNull();
    }

    @Test
    @DisplayName("happy：按关联主单装载命中（发起支付复用判定锚点）")
    void findByOrderId_match_returnsOrder() {
        final PaymentOrderPO po = new PaymentOrderPO();
        when(paymentOrderJpaRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(po));
        final PaymentOrder order = org.mockito.Mockito.mock(PaymentOrder.class);
        when(convertor.convertToRoot(any(PaymentOrderPO.class), any()))
                .thenReturn(order);
        when(callbackLogJpaRepository.findByPaymentOrderIdOrderByIdAsc(any()))
                .thenReturn(List.of());

        final PaymentOrder result = repository.findByOrderId(ORDER_ID);

        assertThat(result).isSameAs(order);
    }

    @Test
    @DisplayName("fail：按关联主单装载不存在返回 null（编排按「补建」处理）")
    void findByOrderId_absent_returnsNull() {
        when(paymentOrderJpaRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        assertThat(repository.findByOrderId(ORDER_ID)).isNull();
    }

    @Test
    @DisplayName("happy：超时扫描返回到期候选（状态/时刻按预期透传，含等号边界）")
    void findDue_expectedStatusAndCutoff_passedThrough() {
        final PaymentOrderPO due = new PaymentOrderPO();
        when(paymentOrderJpaRepository.findByStatusAndTimeoutAtLessThanEqualOrderByTimeoutAtAsc(
                eq(PaymentOrderStatus.PENDING_PAYMENT), any(LocalDateTime.class),
                any(Limit.class)))
                .thenReturn(List.of(due));
        final PaymentOrder candidate = org.mockito.Mockito.mock(PaymentOrder.class);
        when(convertor.convertToRoot(any(PaymentOrderPO.class), any()))
                .thenReturn(candidate);
        when(callbackLogJpaRepository.findByPaymentOrderIdOrderByIdAsc(any()))
                .thenReturn(List.of());

        final List<PaymentOrder> result = repository
                .findDueByStatusAndTimeoutAtBefore(PaymentOrderStatus.PENDING_PAYMENT,
                        NOW, LIMIT);

        assertThat(result).hasSize(1);
        verify(paymentOrderJpaRepository)
                .findByStatusAndTimeoutAtLessThanEqualOrderByTimeoutAtAsc(
                        eq(PaymentOrderStatus.PENDING_PAYMENT), eq(NOW_UTC),
                        eq(Limit.of(LIMIT)));
    }

    @Test
    @DisplayName("critical：无到期候选返回空列表（fail-safe）")
    void findDue_noDue_returnsEmpty() {
        when(paymentOrderJpaRepository.findByStatusAndTimeoutAtLessThanEqualOrderByTimeoutAtAsc(
                eq(PaymentOrderStatus.PENDING_PAYMENT), any(LocalDateTime.class),
                any(Limit.class)))
                .thenReturn(List.of());

        final List<PaymentOrder> result = repository
                .findDueByStatusAndTimeoutAtBefore(PaymentOrderStatus.PENDING_PAYMENT,
                        NOW, LIMIT);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("happy：claim 条件命中（1 行）返回 true")
    void claimTimeout_singleRowAffected_returnsTrue() {
        when(jdbcTemplate.update(anyString(), anyLong(),
                anyString())).thenReturn(1);

        final boolean claimed = repository.claimTimeout(
                9801L, PaymentOrderStatus.PENDING_PAYMENT);

        assertThat(claimed).isTrue();
        verify(jdbcTemplate).update(anyString(), eq(9801L),
                eq(PaymentOrderStatus.PENDING_PAYMENT.name()));
    }

    @Test
    @DisplayName("fail：claim 条件不满足（0 行——已认领/状态迁移/行不存在）返回 false")
    void claimTimeout_noRowAffected_returnsFalse() {
        when(jdbcTemplate.update(anyString(), anyLong(),
                anyString())).thenReturn(0);

        final boolean claimed = repository.claimTimeout(
                9801L, PaymentOrderStatus.PENDING_PAYMENT);

        assertThat(claimed).isFalse();
    }

    @Test
    @DisplayName("happy：clear 无条件幂等（0 行/目标不存在均成功无异常）")
    void clearTimeoutDeadline_idempotent_noError() {
        when(jdbcTemplate.update(anyString(), anyLong())).thenReturn(0);

        assertThatCode(() -> repository.clearTimeoutDeadline(9801L))
                .doesNotThrowAnyException();
        verify(jdbcTemplate).update(anyString(), eq(9801L));
    }

    @Test
    @DisplayName("happy：级联删——留痕行先删、根行后删，返回真实受影响行数 1")
    void deleteByID_cascadesLogsThenRoot_returnsAffectedOne() {
        final PaymentCallbackLogPO log = new PaymentCallbackLogPO();
        when(callbackLogJpaRepository.findByPaymentOrderIdOrderByIdAsc(any()))
                .thenReturn(List.of(log));
        when(paymentOrderJpaRepository.existsById(anyLong())).thenReturn(true);

        assertThat(repository.deleteByID(9801L)).isEqualTo(1);
        verify(callbackLogJpaRepository).deleteAll(List.of(log));
        verify(paymentOrderJpaRepository).deleteById(9801L);
    }

    @Test
    @DisplayName("fail：删除不存在的根行返回 0（真实语义，非契约形）")
    void deleteByID_rootAbsent_returnsZero() {
        when(paymentOrderJpaRepository.existsById(anyLong())).thenReturn(false);

        assertThat(repository.deleteByID(9802L)).isEqualTo(0);
    }
}