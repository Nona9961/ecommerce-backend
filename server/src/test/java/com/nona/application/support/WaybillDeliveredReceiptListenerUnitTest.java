package com.nona.application.support;

import com.nona.domain.logistics.ports.WaybillDelivered;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.ports.OrderCompleted;
import com.nona.domain.order.ports.OrderCompletedEventPublisher;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantPrivilege;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 签收自动完成消费方场景测试（物流签收事件 → 订单完成联动
 * 契约）：
 * <p>
 * happy——事件到达提交异步任务 / 任务执行完成联动（提权段内子单推进
 * + 完成事件发布，载荷 = 子单 + 归属主单）/ 监听线程容错（任务体
 * 异常零感知）；critical——已完成子单幂等短路（不推进不重复发布完成
 * 事件）/ 子单不存在静默跳过；fail——推进失败透传后任务体容错（事件
 * 不发布）/ 执行器拒绝提交不传播。
 * <p>
 * 装配纪律：@BeforeEach 重建被测监听（禁字段初始化 new X(mock)）；
 * 提权事务桩直执行（lenient——短路/静默路径不触达提权段，先例同
 * 形）；Waybill/Sellout 同款事件使用真实构造（事件契约豁免）；桩
 * 纪律：UOE 挡道（onWaybillDelivered/autoCompleteOnDelivered
 * 未接线），提权/执行器通用桩 lenient 豁免，红因纯净 = 100% 实现
 * 缺失；实现后收回精确桩。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class WaybillDeliveredReceiptListenerUnitTest {

    /**
     * 测试标识
     */
    private static final long WAYBILL_ID = 9001L;
    private static final long MASTER_ID = 100L;
    private static final long SUB_ID = 101L;
    private static final long SHOP_ID = 4001L;

    @Mock
    private SubOrderRepository subOrderRepository;

    @Mock
    private OrderFacade orderFacade;

    @Mock
    private OrderCompletedEventPublisher orderCompletedEventPublisher;

    @Mock
    private TenantPrivilege tenantPrivilege;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private Executor waybillEventExecutor;

    /**
     * 被测消费方（不注册 Spring；依赖全 mock，setUp 装配）
     */
    private WaybillDeliveredReceiptListener listener;

    /**
     * 每用例前重建被测消费方（mock 桩在各用例内布置，UOE 挡道面
     * lenient 豁免）。
     */
    @BeforeEach
    void setUp() {
        listener = new WaybillDeliveredReceiptListener(subOrderRepository, orderFacade,
                orderCompletedEventPublisher, tenantPrivilege, transactionTemplate,
                waybillEventExecutor);
        try {
            lenient().when(tenantPrivilege.elevatedInTransaction(eq(transactionTemplate),
                            any(Callable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1, Callable.class).call());
        } catch (final Exception e) {
            throw new IllegalStateException("提权事务 stub 装配失败", e);
        }
    }

    /* ================= fixtures ================= */

    /**
     * 已发货子单（签收联动推进面：待完成）。
     */
    private static SubOrder subShipped() {
        return subWithStatus(SubOrderStatus.SHIPPED);
    }

    /**
     * 已完成子单（幂等短路面）。
     */
    private static SubOrder subCompleted() {
        return subWithStatus(SubOrderStatus.COMPLETED);
    }

    /**
     * 按状态装配合法子单（装载构造器；金额自洽守卫满足）。
     */
    private static SubOrder subWithStatus(SubOrderStatus status) {
        return new SubOrder(SUB_ID, MASTER_ID, SHOP_ID, "SO20260908000" + SUB_ID,
                new AddressSnapshot("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号"),
                new AmountDetail(35000L, 800L, 0L, 35800L),
                List.of(new OrderItem(3001L, 3001L, "测试商品", 7000L, 5, 35000L,
                        null, null, Map.of(), Map.of())),
                status, WAYBILL_ID);
    }

    /**
     * 签收事件（真实构造——事件契约豁免面）。
     */
    private static WaybillDelivered deliveredEvent() {
        return new WaybillDelivered(WAYBILL_ID, SUB_ID);
    }

    /* ================= happy path ================= */

    /**
     * happy-1 事件到达提交异步任务：AFTER_COMMIT 监听方法将自动完成
     * 任务提交到物流事件执行器（异步执行，不阻塞推进线程）。
     */
    @Test
    @DisplayName("签收事件提交异步自动完成任务")
    void onWaybillDelivered_submitsAsyncTask() {
        assertThatCode(() -> listener.onWaybillDelivered(deliveredEvent()))
                .doesNotThrowAnyException();

        verify(waybillEventExecutor).execute(any(Runnable.class));
    }

    /**
     * happy-2 任务执行完成联动：提权事务内子单完成推进 + 完成事件
     * 发布（载荷 = 子单 + 归属主单——完成事件子单粒度语义，与确认/
     * 超时发布同契）。
     */
    @Test
    @DisplayName("任务执行完成联动：autoComplete + 完成事件发布（载荷断言）")
    void onWaybillDelivered_taskAutoCompletesSubOrder() {
        when(subOrderRepository.getByID(SUB_ID)).thenReturn(subShipped());
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(waybillEventExecutor).execute(any(Runnable.class));

        listener.onWaybillDelivered(deliveredEvent());

        verify(orderFacade).autoComplete(SUB_ID);
        final ArgumentCaptor<OrderCompleted> captor = ArgumentCaptor.forClass(OrderCompleted.class);
        verify(orderCompletedEventPublisher).publishOrderCompleted(captor.capture());
        assertThat(captor.getValue().getPayload().subOrderId()).isEqualTo(SUB_ID);
        assertThat(captor.getValue().getPayload().masterOrderId()).isEqualTo(MASTER_ID);
    }

    /**
     * happy-3 监听线程容错：任务体执行编排异常被任务体容错捕获——
     * 监听线程零感知（自动完成失败仅告警，不影响物流主链路）。
     */
    @Test
    @DisplayName("编排异常被任务体容错捕获，监听线程零感知")
    void onWaybillDelivered_orchestrationFailureSwallowed() {
        when(subOrderRepository.getByID(SUB_ID)).thenReturn(subShipped());
        doThrow(new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                "仅已发货子单可完成")).when(orderFacade).autoComplete(SUB_ID);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(waybillEventExecutor).execute(any(Runnable.class));

        assertThatCode(() -> listener.onWaybillDelivered(deliveredEvent()))
                .doesNotThrowAnyException();

        verify(orderCompletedEventPublisher, never()).publishOrderCompleted(any());
    }

    /* ================= critical path ================= */

    /**
     * critical-1 已完成子单幂等短路：重复签收事件/与买家确认、收货
     * 超时完成竞态——已完成子单不推进、不重复发布完成事件（完成事件
     * 单点语义）。
     */
    @Test
    @DisplayName("已完成子单幂等短路：不推进不重复发布完成事件")
    void autoCompleteOnDelivered_alreadyCompleted_shortCircuit() {
        when(subOrderRepository.getByID(SUB_ID)).thenReturn(subCompleted());
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(waybillEventExecutor).execute(any(Runnable.class));

        listener.onWaybillDelivered(deliveredEvent());

        verify(orderFacade, never()).autoComplete(anyLong());
        verify(orderCompletedEventPublisher, never()).publishOrderCompleted(any());
    }

    /**
     * critical-2 子单不存在静默跳过：签收滞后/数据清理/子单不可见属
     * 最终一致联动的常态面——静默跳过不动作（防重复消费与乱序伤害）。
     */
    @Test
    @DisplayName("子单不存在静默跳过（最终一致联动常态面）")
    void autoCompleteOnDelivered_subOrderMissing_silentlySkipped() {
        when(subOrderRepository.getByID(SUB_ID)).thenReturn(null);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(waybillEventExecutor).execute(any(Runnable.class));

        listener.onWaybillDelivered(deliveredEvent());

        verify(orderFacade, never()).autoComplete(anyLong());
        verify(orderCompletedEventPublisher, never()).publishOrderCompleted(any());
    }

    /* ================= fail path ================= */

    /**
     * fail-1 核心编排直接调用面：推进失败（聚合守卫拒绝等）异常透传
     * （含提权事务回滚语义），事件不发布；任务体容错由监听层承载。
     */
    @Test
    @DisplayName("完成推进失败：异常透传，事件不发布")
    void autoCompleteOnDelivered_guardRejected_noEvent() {
        when(subOrderRepository.getByID(SUB_ID)).thenReturn(subShipped());
        doThrow(new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                "仅已发货子单可完成")).when(orderFacade).autoComplete(SUB_ID);

        assertThatCode(() -> listener.autoCompleteOnDelivered(SUB_ID))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code()));

        verify(orderCompletedEventPublisher, never()).publishOrderCompleted(any());
    }

    /**
     * fail-2 执行器拒绝提交（饱和）不向发布侧传播（告警兜底——消费
     * 失败不影响物流主链路）。
     */
    @Test
    @DisplayName("执行器拒绝提交不传播")
    void onWaybillDelivered_submitRejectedSwallowed() {
        doThrow(new RuntimeException("executor saturated"))
                .when(waybillEventExecutor).execute(any(Runnable.class));

        assertThatCode(() -> listener.onWaybillDelivered(deliveredEvent()))
                .doesNotThrowAnyException();
    }
}