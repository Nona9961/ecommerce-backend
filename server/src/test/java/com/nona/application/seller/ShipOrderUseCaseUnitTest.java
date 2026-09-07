package com.nona.application.seller;

import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.domain.logistics.factory.WaybillFactory;
import com.nona.domain.logistics.repo.WaybillRepository;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.ports.OrderFacade;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * 商家发货编排用例场景测试（S10.3 商家标记发货 + 运单创建跨上下文
 * 同事务，红阶段契约）。
 * <p>
 * 覆盖：happy——发货全链路（归属校验 → 幂等短路判定 → 在途守卫 →
 * 提权段内建单 → 运单落库 → 订单推进，编排序与运单装配断言：公司/
 * 单号透传工厂、初始轨迹待发货、创建时刻相对窗口）与单号参数透传面；
 * critical——已发货子单幂等短路（重放/重复点击：无在途查询、无建单、
 * 无推进）、在途运单命中 409 拒绝（一子单一在途）、操作单元仅目标子单
 * （不装载主单与兄弟子单）；fail——子单不存在/归属不符按不存在呈现
 * 404、聚合守卫拒绝与仓储落库失败异常透传（同事务回滚职责在方法事务）。
 * <p>
 * 依赖装配：全部端口/仓储/工厂以 mock 承载（编排契约断言面）；提权
 * 事务以 mock 直执行（事务边界属应用层，由用例注解与提权包装承载，
 * 绿期冒烟测试验证真实回滚）。红阶段失败原因 = 实现缺失（用例方法体
 * 未接线），而非语法/装配错误。
 * <p>
 * 时间断言：运单创建时刻由编排取当前时间传入工厂——断言用相对窗口
 * （工厂收到时刻与断言时刻差 ≤5s），零绝对日期魔法值；运单 fixture
 * 的轨迹节点时间取断言窗口内的固定值（fixture 内部自洽，不参与
 * 编排参数断言）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class ShipOrderUseCaseUnitTest {

    /**
     * 测试店铺 / 主订单 / 子单 / 运单
     */
    private static final long SHOP_A = 4001L;
    private static final long SHOP_B = 4002L;
    private static final long MASTER_ID = 100L;
    private static final long SUB_A = 101L;
    private static final long SUB_B = 102L;
    private static final long WAYBILL_ID = 9001L;
    private static final long TRACK_ID = 9002L;

    /**
     * 承运公司与运单号（录入契约面，TD-13 模拟物流单号 SF 前缀）
     */
    private static final String COMPANY = "顺丰速运";
    private static final String TRACKING_NO = "SF202609080001";

    @Mock
    private SubOrderRepository subOrderRepository;

    @Mock
    private WaybillRepository waybillRepository;

    @Mock
    private WaybillFactory waybillFactory;

    @Mock
    private OrderFacade orderFacade;

    @Mock
    private TenantPrivilege tenantPrivilege;

    @Mock
    private TransactionTemplate transactionTemplate;

    /**
     * 被测用例（红阶段不注册 Spring；依赖全 mock，setUp 装配）。
     */
    private ShipOrderUseCase useCase;

    /**
     * 每用例前重建被测用例（mock 桩在各用例内布置，桩全部被使用）。
     */
    @BeforeEach
    void setUp() {
        useCase = new ShipOrderUseCase(subOrderRepository, waybillRepository,
                waybillFactory, orderFacade, tenantPrivilege, transactionTemplate);
        try {
            lenient().when(tenantPrivilege.elevatedInTransaction(eq(transactionTemplate), any(Callable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1, Callable.class).call());
        } catch (final Exception e) {
            throw new IllegalStateException("提权事务 stub 装配失败", e);
        }
    }

    /* ================= fixtures ================= */

    /**
     * 已支付未发货子单 A（SKU 3001 × 2 + SKU 3002 × 3，无运单引用）。
     */
    private static SubOrder subPaidA() {
        return subWithStatus(SUB_A, SHOP_A, 35000L, 800L,
                SubOrderStatus.PAID, null);
    }

    /**
     * 已发货子单 A（运单已定型——幂等短路面）。
     */
    private static SubOrder subShippedA() {
        return subWithStatus(SUB_A, SHOP_A, 35000L, 800L,
                SubOrderStatus.SHIPPED, WAYBILL_ID);
    }

    /**
     * 按状态装配合法子单（装载构造器，waybillId 可空；SKU 按子单 ID
     * 派生——单个订单项 price × qty = goods 恒等，金额自洽守卫满足）。
     */
    private static SubOrder subWithStatus(Long subId, Long shopId, long goods,
                                          long freight, SubOrderStatus status,
                                          Long waybillId) {
        return new SubOrder(subId, MASTER_ID, shopId, "SO20260908000" + subId,
                address(), amount(goods, freight, goods + freight),
                List.of(item(subId * 1000L, goods / 5, 5)), status, waybillId);
    }

    /**
     * 待发货运单 fixture（形态自洽：末条轨迹状态 == 运单当前状态，
     * 构造守卫满足；轨迹节点时间取执行窗口内固定值，不参与断言）。
     */
    private static Waybill waybillPending() {
        return new Waybill(WAYBILL_ID, SUB_A, COMPANY, TRACKING_NO,
                WaybillStatus.PENDING_SHIPMENT,
                List.of(new WaybillTrack(TRACK_ID, WAYBILL_ID,
                        WaybillStatus.PENDING_SHIPMENT, LocalDateTime.now(),
                        "运单创建，等待发货")));
    }

    private static OrderItem item(Long skuId, long price, int qty) {
        return new OrderItem(skuId, skuId, "测试商品" + skuId, price, qty, price * qty,
                null, null, Map.of(), Map.of());
    }

    private static AmountDetail amount(long goods, long freight, long paid) {
        return new AmountDetail(goods, freight, 0L, paid);
    }

    private static AddressSnapshot address() {
        return new AddressSnapshot("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号");
    }

    /**
     * 创建时刻相对窗口断言（编排取当前时间传工厂——与断言时刻差 ≤5s，
     * 零绝对魔法值）。
     */
    private static LocalDateTime nearNow() {
        return argThat(time -> Duration.between(time, LocalDateTime.now()).abs()
                .compareTo(Duration.ofSeconds(5)) <= 0);
    }

    /* ================= happy path ================= */

    /**
     * happy-1 商家发货全链路：归属校验 → 短路判定 → 在途守卫 → 提权段内
     * 建单 → 运单落库 → 订单推进（编排序断言；工厂受参：子单/公司/单号/
     * 创建时刻相对窗口；运单 ID 引用定型 markShipped 双参契约）。
     */
    @Test
    @DisplayName("商家发货全链路：建单→运单落库→子单推进（编排序 + 运单装配）")
    void shipByMerchant_paidSubOrder_fullChain() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subPaidA());
        when(waybillRepository.findInTransitBySubOrderId(SUB_A)).thenReturn(Optional.empty());
        final Waybill waybill = waybillPending();
        when(waybillFactory.createWaybill(eq(SUB_A), eq(COMPANY), eq(TRACKING_NO), any(LocalDateTime.class)))
                .thenReturn(waybill);

        useCase.shipByMerchant(SHOP_A, SUB_A, COMPANY, TRACKING_NO);

        final InOrder inOrder = inOrder(waybillRepository, orderFacade);
        verify(waybillFactory).createWaybill(eq(SUB_A), eq(COMPANY), eq(TRACKING_NO), nearNow());
        inOrder.verify(waybillRepository).save(waybill);
        inOrder.verify(orderFacade).markShipped(SUB_A, WAYBILL_ID);
    }

    /**
     * happy-2 不同公司/单号参数透传：录入契约面（S10.3② 物流公司与单号
     * 展示源）——货物由其它承运发出时，公司/单号仍原样透传工厂定型；
     * 运单创建后初始轨迹待发货（fixture 形态自洽即追认初始轨迹装配）。
     */
    @Test
    @DisplayName("不同承运/单号透传工厂定型，初始轨迹待发货")
    void shipByMerchant_otherCarrier_paramsPassedThrough() {
        final String otherCompany = "圆通速递";
        final String otherTrackingNo = "YT202609080002";
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subPaidA());
        when(waybillRepository.findInTransitBySubOrderId(SUB_A)).thenReturn(Optional.empty());
        final Waybill waybill = waybillPending();
        when(waybillFactory.createWaybill(eq(SUB_A), eq(otherCompany), eq(otherTrackingNo),
                any(LocalDateTime.class))).thenReturn(waybill);

        useCase.shipByMerchant(SHOP_A, SUB_A, otherCompany, otherTrackingNo);

        verify(waybillFactory).createWaybill(eq(SUB_A), eq(otherCompany),
                eq(otherTrackingNo), nearNow());
        verify(waybillRepository).save(waybill);
        verify(orderFacade).markShipped(SUB_A, WAYBILL_ID);
    }

    /* ================= critical path ================= */

    /**
     * critical-1 已发货子单再次发货：幂等短路成功——不查在途运单、不建
     * 单、不落库、不推进订单（重复点击/请求重放常态路径，不重复建运单；
     * ConfirmReceiptUseCase 已完成短路同构）。
     */
    @Test
    @DisplayName("已发货子单重复发货：幂等短路，无在途查询/建单/推进")
    void shipByMerchant_alreadyShipped_idempotentShortCircuit() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subShippedA());

        useCase.shipByMerchant(SHOP_A, SUB_A, COMPANY, TRACKING_NO);

        verify(waybillRepository, never()).findInTransitBySubOrderId(anyLong());
        verify(waybillFactory, never()).createWaybill(any(), any(), any(), any());
        verify(waybillRepository, never()).save(any());
        verify(orderFacade, never()).markShipped(anyLong(), anyLong());
        // 短路面以写动作四连 never 收口（提权段是这些写动作的容器，无写即无提权段）
    }

    /**
     * critical-2 在途运单命中：子单已支付但已有在途运单（数据异常/并发
     * 窗口防御）→ 一子单一在途拒绝（409），不建单不推进。
     */
    @Test
    @DisplayName("在途运单命中：一子单一在途拒绝（sub_order_conflict 409）")
    void shipByMerchant_inTransitWaybillExists_conflict() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subPaidA());
        when(waybillRepository.findInTransitBySubOrderId(SUB_A))
                .thenReturn(Optional.of(waybillPending()));

        assertThatThrownBy(() -> useCase.shipByMerchant(SHOP_A, SUB_A, COMPANY, TRACKING_NO))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThatError(error, EcommerceBusinessCode.LOGISTICS_SUB_ORDER_CONFLICT.code(), 409));

        verify(waybillFactory, never()).createWaybill(any(), any(), any(), any());
        verify(waybillRepository, never()).save(any());
        verify(orderFacade, never()).markShipped(anyLong(), anyLong());
    }

    /**
     * critical-3 操作单元仅目标子单：发货编排按子单装载与推进——不装载
     * 主单、不触碰兄弟子单（主单状态派生由订单门面内部承载，本用例
     * 只传目标子单引用）。
     */
    @Test
    @DisplayName("操作单元仅目标子单：不装载主单与兄弟子单")
    void shipByMerchant_singleSubOrderUnit_noMasterLoading() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subPaidA());
        when(waybillRepository.findInTransitBySubOrderId(SUB_A)).thenReturn(Optional.empty());
        when(waybillFactory.createWaybill(any(), any(), any(), any()))
                .thenReturn(waybillPending());

        useCase.shipByMerchant(SHOP_A, SUB_A, COMPANY, TRACKING_NO);

        verify(subOrderRepository, never()).getByMasterOrderId(anyLong());
        verify(orderFacade).markShipped(SUB_A, WAYBILL_ID);
    }

    /* ================= fail path ================= */

    /**
     * fail-1 子单不存在：按不存在呈现（404），不触达在途守卫与提权段。
     */
    @Test
    @DisplayName("子单不存在：按不存在呈现（sub_not_found 404）")
    void shipByMerchant_subOrderMissing_notFound() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(null);

        assertThatThrownBy(() -> useCase.shipByMerchant(SHOP_A, SUB_A, COMPANY, TRACKING_NO))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThatError(error, EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(), 404));

        verify(waybillRepository, never()).findInTransitBySubOrderId(anyLong());
        verify(waybillFactory, never()).createWaybill(any(), any(), any(), any());
        verify(orderFacade, never()).markShipped(anyLong(), anyLong());
    }

    /**
     * fail-2 归属不符：操作者店铺（SHOP_B）与子单归属店铺（SHOP_A）不符
     * → 按不存在呈现（404，防越权与存在性泄露——租户过滤兜底先行，
     * 编排显式校验第二道防线）。
     */
    @Test
    @DisplayName("归属不符：按不存在呈现（防越权，不泄露归属）")
    void shipByMerchant_shopMismatch_notFound() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subPaidA());

        assertThatThrownBy(() -> useCase.shipByMerchant(SHOP_B, SUB_A, COMPANY, TRACKING_NO))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThatError(error, EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(), 404));

        verify(waybillRepository, never()).findInTransitBySubOrderId(anyLong());
        verify(waybillFactory, never()).createWaybill(any(), any(), any(), any());
        verify(orderFacade, never()).markShipped(anyLong(), anyLong());
    }

    /**
     * fail-3 订单推进守卫拒绝透传：订单门面抛聚合守卫业务异常（如仅已
     * 支付可发货——并发窗口下重复推进命中）→ 原样透传（编排不吞，
     * 同事务回滚职责在方法事务），运单回滚不落库。
     */
    @Test
    @DisplayName("订单推进守卫拒绝：sub_status_illegal 原样透传")
    void shipByMerchant_domainGuardRejected_passedThrough() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subPaidA());
        when(waybillRepository.findInTransitBySubOrderId(SUB_A)).thenReturn(Optional.empty());
        final Waybill waybill = waybillPending();
        when(waybillFactory.createWaybill(any(), any(), any(), any()))
                .thenReturn(waybill);
        doThrow(new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                "仅已支付子单可发货")).when(orderFacade).markShipped(SUB_A, WAYBILL_ID);

        assertThatThrownBy(() -> useCase.shipByMerchant(SHOP_A, SUB_A, COMPANY, TRACKING_NO))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThatError(error, EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(), 400));

        verify(waybillRepository).save(waybill);
    }

    /**
     * fail-4 运单落库失败透传：仓储保存抛业务异常（落库防线）→ 原样
     * 透传（不出现「订单已推进但运单未落」的半程态——方法事务整体回滚）。
     */
    @Test
    @DisplayName("运单落库失败：异常原样透传（整体回滚职责在方法事务）")
    void shipByMerchant_waybillSaveFailed_passedThrough() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subPaidA());
        when(waybillRepository.findInTransitBySubOrderId(SUB_A)).thenReturn(Optional.empty());
        final Waybill waybill = waybillPending();
        when(waybillFactory.createWaybill(any(), any(), any(), any()))
                .thenReturn(waybill);
        doThrow(new BusinessException(EcommerceBusinessCode.LOGISTICS_WAYBILL_INVALID.code(),
                "运单落库失败")).when(waybillRepository).save(waybill);

        assertThatThrownBy(() -> useCase.shipByMerchant(SHOP_A, SUB_A, COMPANY, TRACKING_NO))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThatError(error, EcommerceBusinessCode.LOGISTICS_WAYBILL_INVALID.code(), 400));

        verify(orderFacade, never()).markShipped(anyLong(), anyLong());
    }

    /**
     * 业务异常断言助手（码 + HTTP 状态）。
     */
    private static void assertThatError(BusinessException error, String code, int httpStatus) {
        org.assertj.core.api.Assertions.assertThat(error.getBusinessCode()).isEqualTo(code);
        org.assertj.core.api.Assertions.assertThat(error.getHttpStatus()).isEqualTo(httpStatus);
    }
}