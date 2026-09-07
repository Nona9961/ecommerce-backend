package com.nona.application.mall;

import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.ports.OrderCompleted;
import com.nona.domain.order.ports.OrderCompletedEventPublisher;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantPrivilege;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 确认收货编排用例场景测试（B9.3 买家主动确认收货 + B9.4③ 收货超时
 * 自动完成 + 完成事件发布，红阶段契约）。
 * <p>
 * 覆盖：happy——买家确认全链路（归属校验 → 订单完成推进 → 完成事件
 * 发布，编排序与载荷断言）与超时入口（无身份校验，同一编排）与多子单
 * 子单粒度推进；critical——已完成子单幂等短路（确认重放/超时重扫：
 * 无推进、无重复事件）、混态多子单部分完成、归属校验先于幂等短路；
 * fail——子单不存在/归属不符/主单缺失 404、聚合守卫拒绝（未发货确认
 * 非法迁移）异常透传且事件不发布、事件发布失败异常透传（同事务回滚）。
 * <p>
 * 依赖装配：全部端口/仓储以 mock 承载（编排契约断言面）；提权事务以
 * mock 直执行（事务边界属应用层，由用例注解与提权包装承载，绿期集成
 * 测试验证真实回滚）。红阶段失败原因 = 实现缺失（用例方法体 UOE）。
 * <p>
 * 时间断言：本 WU 无截止时间逻辑（收货超时 deadline 注册归超时引擎
 * WU，autoComplete 消费时间戳由引擎侧承载）——零绝对日期魔法值；
 * 事件构造时间戳不在单测断言面（载荷断言为准）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class ConfirmReceiptUseCaseUnitTest {

    /**
     * 测试买家 / 主订单 / 店铺 / 子单 / 运单
     */
    private static final long BUYER_ID = 200L;
    private static final long MASTER_ID = 100L;
    private static final long SHOP_A = 4001L;
    private static final long SHOP_B = 4002L;
    private static final long SUB_A = 101L;
    private static final long SUB_B = 102L;
    private static final long WAYBILL_A = 9001L;
    private static final long WAYBILL_B = 9002L;

    @Mock
    private MasterOrderRepository masterOrderRepository;

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

    /**
     * 被测用例（红阶段不注册 Spring；依赖全 mock，setUp 装配）。
     */
    private ConfirmReceiptUseCase useCase;

    /**
     * 每用例前重建被测用例（mock 桩在各用例内布置，桩全部被使用）。
     */
    @BeforeEach
    void setUp() {
        useCase = new ConfirmReceiptUseCase(masterOrderRepository, subOrderRepository,
                orderFacade, orderCompletedEventPublisher, tenantPrivilege,
                transactionTemplate);
        try {
            lenient().when(tenantPrivilege.elevatedInTransaction(eq(transactionTemplate), any(Callable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1, Callable.class).call());
        } catch (final Exception e) {
            throw new IllegalStateException("提权事务 stub 装配失败", e);
        }
    }

    /* ================= fixtures ================= */

    /**
     * 单子单主单基线（A 店 35800；主单合计 goods 35000 / freight 800 /
     * paid 35800）。
     */
    private static MasterOrder masterOneSub() {
        return master(MASTER_ID, List.of(SUB_A),
                List.of(amount(35000L, 800L, 35800L)));
    }

    /**
     * 双子单主单基线（A 店 35800 + B 店 10000；主单合计 goods 45000 /
     * freight 800 / paid 45800）。
     */
    private static MasterOrder masterTwoSubs() {
        return master(MASTER_ID, List.of(SUB_A, SUB_B),
                List.of(amount(35000L, 800L, 35800L), amount(10000L, 0L, 10000L)));
    }

    /**
     * 按子单引用集合装配合法主单（金额恒等式以子单金额投影推导——摘要
     * 四维 = Σ 子单投影，与门面测试 pendingMaster 自洽写法同构；投影
     * 传入构造器做恒等式守卫）。
     */
    private static MasterOrder master(Long id, List<Long> subOrderIds,
                                      List<AmountDetail> subAmounts) {
        final long goods = subAmounts.stream().mapToLong(AmountDetail::getGoodsAmount).sum();
        final long freight = subAmounts.stream().mapToLong(AmountDetail::getFreightAmount).sum();
        final long discount = subAmounts.stream().mapToLong(AmountDetail::getDiscount).sum();
        final long paid = subAmounts.stream().mapToLong(AmountDetail::getPaidAmount).sum();
        return new MasterOrder(id, "ORD202609070001", BUYER_ID,
                address(), amount(goods, freight, discount, paid),
                subOrderIds, subAmounts, MasterOrderStatus.PENDING_PAYMENT);
    }

    /**
     * 已发货子单 A（SKU 3001 × 2 + SKU 3002 × 3，运单已定型）。
     */
    private static SubOrder subShippedA() {
        return subWithStatus(SUB_A, SHOP_A, 35000L, 800L,
                SubOrderStatus.SHIPPED, WAYBILL_A);
    }

    /**
     * 已支付未发货子单 A（非法完成面：聚合守卫拒绝）。
     */
    private static SubOrder subPaidA() {
        return subWithStatus(SUB_A, SHOP_A, 35000L, 800L,
                SubOrderStatus.PAID, null);
    }

    /**
     * 已完成子单 A（幂等短路面）。
     */
    private static SubOrder subCompletedA() {
        return subWithStatus(SUB_A, SHOP_A, 35000L, 800L,
                SubOrderStatus.COMPLETED, WAYBILL_A);
    }

    /**
     * 已发货子单 B（混态面）。
     */
    private static SubOrder subShippedB() {
        return subWithStatus(SUB_B, SHOP_B, 10000L, 0L,
                SubOrderStatus.SHIPPED, WAYBILL_B);
    }

    /**
     * 按状态装配合法子单（装载构造器，WAYBILL 可空；SKU 按子单 ID 派生
     * ——单个订单项 price × qty = goods 恒等，金额自洽守卫满足）。
     */
    private static SubOrder subWithStatus(Long subId, Long shopId, long goods,
                                          long freight, SubOrderStatus status,
                                          Long waybillId) {
        return new SubOrder(subId, MASTER_ID, shopId, "SO20260907000" + subId,
                address(), amount(goods, freight, 0L, goods + freight),
                List.of(item(subId * 1000L, goods / 5, 5)), status, waybillId);
    }

    private static OrderItem item(Long skuId, long price, int qty) {
        return new OrderItem(skuId, skuId, "测试商品" + skuId, price, qty, price * qty,
                null, null, Map.of(), Map.of());
    }

    private static AmountDetail amount(long goods, long freight, long paid) {
        return new AmountDetail(goods, freight, 0L, paid);
    }

    private static AmountDetail amount(long goods, long freight, long discount, long paid) {
        return new AmountDetail(goods, freight, discount, paid);
    }

    private static AddressSnapshot address() {
        return new AddressSnapshot("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号");
    }

    /**
     * 完成事件载荷断言（subOrderId + masterOrderId 定位引用）。
     */
    private static OrderCompleted completedOf(Long subOrderId) {
        return argThat(event -> event.getPayload().subOrderId() == subOrderId
                && event.getPayload().masterOrderId() == MASTER_ID);
    }

    /* ================= happy path ================= */

    /**
     * happy-1 买家确认全链路：归属校验（子单 → 主单 → 买家匹配）→
     * 提权段内订单完成推进 → 完成事件发布（编排序 + 载荷断言：
     * subOrderId/masterOrderId 定位引用）。
     */
    @Test
    @DisplayName("买家确认收货全链路：归属校验 → 订单完成推进 → 完成事件发布")
    void confirmByBuyer_shippedSubOrder_fullChain() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subShippedA());
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(masterOneSub());

        useCase.confirmByBuyer(BUYER_ID, SUB_A);

        final InOrder inOrder = inOrder(orderFacade, orderCompletedEventPublisher);
        inOrder.verify(orderFacade).autoComplete(SUB_A);
        inOrder.verify(orderCompletedEventPublisher).publishOrderCompleted(completedOf(SUB_A));
    }

    /**
     * happy-2 收货超时入口：无买家身份校验（不装载主单），订单完成
     * 推进 + 完成事件发布与买家入口共用编排（B9.4③ 超时调度复用面）。
     */
    @Test
    @DisplayName("收货超时自动完成：无身份校验，推进与事件发布共用编排")
    void autoCompleteByTimeout_shippedSubOrder_fullChain() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subShippedA());

        useCase.autoCompleteByTimeout(SUB_A);

        verify(orderFacade).autoComplete(SUB_A);
        verify(orderCompletedEventPublisher).publishOrderCompleted(completedOf(SUB_A));
    }

    /**
     * happy-3 多子单主单确认单个子单：子单粒度推进与事件发布——只推进
     * 目标子单（另一子单不受影响），事件载荷 = 目标子单（部分完成属
     * 正常中间态，主单派生由门面在推进时重算）。
     */
    @Test
    @DisplayName("多子单主单：确认单子单仅推进目标子单，事件按子单粒度发布")
    void confirmByBuyer_multiSubOrder_subOrderGranularity() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subShippedA());
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(masterTwoSubs());

        useCase.confirmByBuyer(BUYER_ID, SUB_A);

        verify(orderFacade).autoComplete(SUB_A);
        verify(orderFacade, never()).autoComplete(SUB_B);
        verify(orderCompletedEventPublisher).publishOrderCompleted(completedOf(SUB_A));
    }

    /* ================= critical path ================= */

    /**
     * critical-1 已完成子单再确认：幂等短路成功——不再触发订单推进与
     * 事件发布（买家确认重放常态路径；归属校验仍先执行）。
     */
    @Test
    @DisplayName("已完成子单再确认：幂等短路，无重复推进与重复事件")
    void confirmByBuyer_alreadyCompleted_idempotentShortCircuit() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subCompletedA());
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(masterOneSub());

        useCase.confirmByBuyer(BUYER_ID, SUB_A);

        verify(orderFacade, never()).autoComplete(anyLong());
        verify(orderCompletedEventPublisher, never()).publishOrderCompleted(any());
    }

    /**
     * critical-2 超时重扫命中已完成子单：幂等短路成功且不重复发布事件
     * （B9.4③ 超时 handler 幂等常态路径——Phase-II 消费方不收重复完成
     * 事件）。
     */
    @Test
    @DisplayName("超时重扫已完成子单：幂等短路，无重复推进与重复事件")
    void autoCompleteByTimeout_alreadyCompleted_idempotent() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subCompletedA());

        useCase.autoCompleteByTimeout(SUB_A);

        verify(orderFacade, never()).autoComplete(anyLong());
        verify(orderCompletedEventPublisher, never()).publishOrderCompleted(any());
    }

    /**
     * critical-3 混态多子单：SUB_A 已完成 + SUB_B 已发货，确认 SUB_B——
     * 幂等判定按目标子单（SUB_A 的完成态不影响 SUB_B 首次完成推进与
     * 事件发布）。
     */
    @Test
    @DisplayName("混态多子单：已完成子单不影响其他子单的首次完成")
    void confirmByBuyer_mixedMultiSub_targetSubCompleted() {
        when(subOrderRepository.getByID(SUB_B)).thenReturn(subShippedB());
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(masterTwoSubs());

        useCase.confirmByBuyer(BUYER_ID, SUB_B);

        verify(orderFacade).autoComplete(SUB_B);
        verify(orderCompletedEventPublisher).publishOrderCompleted(completedOf(SUB_B));
    }

    /**
     * critical-4 归属校验先于幂等短路：他人对已完成子单的确认同样按
     * 不存在拒绝（幂等短路不放行越权请求——短路仅对本买家幂等）。
     */
    @Test
    @DisplayName("归属校验先于幂等短路：他人确认已完成子单按不存在拒绝")
    void confirmByBuyer_buyerMismatch_onCompletedSub_rejected() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subCompletedA());
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(masterOneSub());

        assertThatThrownBy(() -> useCase.confirmByBuyer(999L, SUB_A))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThatError(error, EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(), 404);
                });
        verify(orderFacade, never()).autoComplete(anyLong());
        verify(orderCompletedEventPublisher, never()).publishOrderCompleted(any());
    }

    /* ================= fail path ================= */

    /**
     * fail-1 子单不存在：404 按不存在呈现（order.sub_not_found），
     * 无任何编排动作（防存在性泄露；主单也不装载）。
     */
    @Test
    @DisplayName("买家确认子单不存在：404 按不存在呈现")
    void confirmByBuyer_subNotFound_rejected() {
        when(subOrderRepository.getByID(999L)).thenReturn(null);

        assertThatThrownBy(() -> useCase.confirmByBuyer(BUYER_ID, 999L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThatError(error, EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(), 404);
                });
        verify(masterOrderRepository, never()).getByID(anyLong());
        verify(orderFacade, never()).autoComplete(anyLong());
        verify(orderCompletedEventPublisher, never()).publishOrderCompleted(any());
    }

    /**
     * fail-2 归属不符：他人主单子单按主单不存在呈现（防越权与存在性
     * 泄露，CancelOrderUseCase 同先例）。
     */
    @Test
    @DisplayName("归属不符：按主单不存在呈现")
    void confirmByBuyer_buyerMismatch_rejected() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subShippedA());
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(masterOneSub());

        assertThatThrownBy(() -> useCase.confirmByBuyer(999L, SUB_A))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThatError(error, EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(), 404);
                });
        verify(orderFacade, never()).autoComplete(anyLong());
        verify(orderCompletedEventPublisher, never()).publishOrderCompleted(any());
    }

    /**
     * fail-3 子单存在但主单缺失：404（数据异常防御，不静默；防脏数据
     * 链路静默推进）。
     */
    @Test
    @DisplayName("子单存在但主单缺失：404 防御")
    void confirmByBuyer_masterMissing_rejected() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subShippedA());
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(null);

        assertThatThrownBy(() -> useCase.confirmByBuyer(BUYER_ID, SUB_A))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThatError(error, EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(), 404);
                });
        verify(orderFacade, never()).autoComplete(anyLong());
    }

    /**
     * fail-4 超时入口子单不存在：404（系统触发数据异常防御，不静默）。
     */
    @Test
    @DisplayName("超时入口子单不存在：404")
    void autoCompleteByTimeout_subNotFound_rejected() {
        when(subOrderRepository.getByID(999L)).thenReturn(null);

        assertThatThrownBy(() -> useCase.autoCompleteByTimeout(999L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThatError(error, EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(), 404);
                });
        verify(orderFacade, never()).autoComplete(anyLong());
        verify(orderCompletedEventPublisher, never()).publishOrderCompleted(any());
    }

    /**
     * fail-5 聚合守卫拒绝（未发货子单确认 = 非法迁移 B9.3 ① 语义内建）：
     * 订单侧非法迁移异常原样透传——完成事件不发布（同事务整体回滚，
     * 杜绝半程副作用）。
     */
    @Test
    @DisplayName("未发货子单确认：聚合守卫异常透传，事件不发布")
    void confirmByBuyer_illegalState_guardRejectsAndAborts() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subPaidA());
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(masterOneSub());
        doThrow(new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                "仅已发货子单可标记完成", 400))
                .when(orderFacade).autoComplete(SUB_A);

        assertThatThrownBy(() -> useCase.confirmByBuyer(BUYER_ID, SUB_A))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThatError(error, EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(), 400);
                });
        verify(orderCompletedEventPublisher, never()).publishOrderCompleted(any());
    }

    /**
     * fail-6 完成事件发布失败：异常原样透传（推进已发生、发布在后——
     * 发布失败按同事务整体回滚承诺，事件不承担可靠性职责但发布调用
     * 面失败不静默）。
     */
    @Test
    @DisplayName("完成事件发布失败：异常透传")
    void confirmByBuyer_publishFailure_aborts() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subShippedA());
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(masterOneSub());
        doThrow(new BusinessException("order.event_publish_failed", "完成事件发布失败", 500))
                .when(orderCompletedEventPublisher).publishOrderCompleted(any(OrderCompleted.class));

        assertThatThrownBy(() -> useCase.confirmByBuyer(BUYER_ID, SUB_A))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThatError(error, "order.event_publish_failed", 500));
        verify(orderFacade).autoComplete(SUB_A);
    }

    /**
     * 业务异常断言助手（码 + HTTP 状态）。
     */
    private static void assertThatError(BusinessException error, String code, int httpStatus) {
        org.assertj.core.api.Assertions.assertThat(error.getBusinessCode()).isEqualTo(code);
        org.assertj.core.api.Assertions.assertThat(error.getHttpStatus()).isEqualTo(httpStatus);
    }
}