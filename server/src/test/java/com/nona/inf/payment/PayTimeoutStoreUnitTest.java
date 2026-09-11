package com.nona.inf.payment;

import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.inf.timeout.TimeoutTask;
import com.nona.inf.timeout.TimeoutType;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付超时数据端口场景测试（ORDER_PAY：payment_order 表 deadline 列
 * 承载面，契约）：
 * <p>
 * happy——findDue 经仓储超时扫描面按预期态 PENDING_PAYMENT 过滤并映射
 * 候选（id=payment_order 主键 / target=主订单 ID）；claim 条件性认领
 * （透传预期态复查）；clearDeadline 成功闭环（清理截止时间与认领位）；
 * critical——claim 条件不满足（他方已认领/状态已迁移）false 透传、目标
 * id/target 参数透传正确；fail——仓储扫描/认领异常原样透传。
 * <p>
 * 装配纪律：端口为普通类（既定装配纪律：
 * 构造器注入仓储 mock（@BeforeEach 重建，禁字段初始化）；桩纪律——
 * 以 lenient 豁免 UOE 挡道面，已按本文件断言面逐桩收回精确桩
 * （零豁免）。
 * <p>
 * 时间断言：now 为测试固定时刻（fixture 输入，非断言魔法值）；全部断言
 * 以同一 now 变量相对比较（零绝对日期魔法值）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class PayTimeoutStoreUnitTest {

    /**
     * 到期支付单主键 / 关联主单 ID / 支付单号
     */
    private static final long PAYMENT_ID = 501L;
    private static final long MASTER_ID = 100L;
    private static final String PAY_NO = "PAY202609080001";

    /**
     * 扫描时刻 fixture（findDue 透传断言基准；全部断言相对本变量）
     */
    private static final Instant NOW = Instant.parse("2026-09-08T12:30:00Z");

    /**
     * 单轮扫描上限（引擎 SCAN_LIMIT 语义透传）
     */
    private static final int LIMIT = 100;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    /**
     * 被测数据端口（setUp 重建）
     */
    private PayTimeoutStore store;

    /**
     * 每用例前重建被测端口（mock 桩在各用例内布置）。
     */
    @BeforeEach
    void setUp() {
        store = new PayTimeoutStore(paymentOrderRepository);
    }

    /* ================= fixtures ================= */

    /**
     * 到期待支付支付单（deadline 已到：timeoutAt = NOW - 1s，相对派生）。
     */
    private static PaymentOrder duePayOrder() {
        return new PaymentOrder(PAYMENT_ID, PAY_NO, MASTER_ID, 45_800L,
                "MOCK", NOW.minusSeconds(1));
    }

    /* ================= happy path ================= */

    @Test
    @DisplayName("happy-1 type 注册：数据端口声明 ORDER_PAY（引擎按类型路由）")
    void type_isOrderPay() {
        assertThat(store.type()).isEqualTo(TimeoutType.ORDER_PAY);
    }

    @Test
    @DisplayName("happy-2 findDue 扫描：预期态 PENDING_PAYMENT + 截止时刻透传，候选映射 id=支付单主键 / target=主订单 ID")
    void findDue_forwardsScanWithExpectedStatus() {
        when(paymentOrderRepository.findDueByStatusAndTimeoutAtBefore(
                        eq(PaymentOrderStatus.PENDING_PAYMENT), eq(NOW), eq(LIMIT)))
                .thenReturn(List.of(duePayOrder()));

        final List<TimeoutTask<Long>> due = store.findDue(NOW, LIMIT);

        assertThat(due).containsExactly(new TimeoutTask<>(PAYMENT_ID, MASTER_ID));
        verify(paymentOrderRepository).findDueByStatusAndTimeoutAtBefore(
                PaymentOrderStatus.PENDING_PAYMENT, NOW, LIMIT);
    }

    @Test
    @DisplayName("happy-3 claim 认领：条件 UPDATE 成功（affected=1）→ true 透传（引擎才 fire）")
    void claim_successWhenAffected() {
        when(paymentOrderRepository.claimTimeout(PAYMENT_ID,
                        PaymentOrderStatus.PENDING_PAYMENT))
                .thenReturn(true);

        assertThat(store.claim(new TimeoutTask<>(PAYMENT_ID, MASTER_ID))).isTrue();
        verify(paymentOrderRepository).claimTimeout(PAYMENT_ID,
                PaymentOrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("happy-4 clearDeadline 闭环：处理成功后清理截止时间与认领位（p2-9 语义）")
    void clearDeadline_forwardsById() {
        store.clearDeadline(new TimeoutTask<>(PAYMENT_ID, MASTER_ID));

        verify(paymentOrderRepository).clearTimeoutDeadline(PAYMENT_ID);
    }

    /* ================= critical path ================= */

    @Test
    @DisplayName("critical-1 claim 条件不满足（他方已认领/状态已迁移）→ false 透传，引擎跳过不 fire")
    void claim_falseWhenNotAffected() {
        when(paymentOrderRepository.claimTimeout(PAYMENT_ID,
                        PaymentOrderStatus.PENDING_PAYMENT))
                .thenReturn(false);

        assertThat(store.claim(new TimeoutTask<>(PAYMENT_ID, MASTER_ID))).isFalse();
    }

    @Test
    @DisplayName("critical-2 findDue 无到期候选 → 空列表（引擎空转零操作）")
    void findDue_emptyWhenNoCandidates() {
        when(paymentOrderRepository.findDueByStatusAndTimeoutAtBefore(
                        eq(PaymentOrderStatus.PENDING_PAYMENT), eq(NOW), eq(LIMIT)))
                .thenReturn(List.of());

        assertThat(store.findDue(NOW, LIMIT)).isEmpty();
    }

    /* ================= fail path ================= */

    @Test
    @DisplayName("fail-1 仓储扫描异常原样透传（引擎侧捕获记录，下轮重扫）")
    void findDue_propagatesRepositoryException() {
        final RuntimeException boom = new IllegalStateException("扫描失败");
        when(paymentOrderRepository.findDueByStatusAndTimeoutAtBefore(
                        any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(boom);

        assertThatThrownBy(() -> store.findDue(NOW, LIMIT)).isSameAs(boom);
    }

    @Test
    @DisplayName("fail-2 仓储认领异常原样透传（claim 抛错 → 事务回滚，下轮重扫再认领）")
    void claim_propagatesRepositoryException() {
        final RuntimeException boom = new IllegalStateException("认领失败");
        when(paymentOrderRepository.claimTimeout(
                        org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenThrow(boom);

        assertThatThrownBy(() -> store.claim(new TimeoutTask<>(PAYMENT_ID, MASTER_ID)))
                .isSameAs(boom);
    }
}