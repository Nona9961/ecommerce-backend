package com.nona.application.mall;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.mall.MallOrderStatus;
import com.nona.api.mall.OrderView;
import com.nona.api.mall.WaybillView;
import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.domain.logistics.repo.WaybillRepository;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 买家订单查询用例场景测试（列表/详情/运单查询契约）。
 * <p>
 * 覆盖：happy——列表主流程（分页 + 状态过滤 + 子单/店铺名/支付单装配）、
 * 详情（含待支付支付单）、运单（轨迹 + 商品行装配）；critical——tab 全量
 * 语义、空结果页、非待支付 payment=null、无运单 404、createdAt 装载回传、
 * 派生状态透传；fail——主单不存在/归属不符（order.master_not_found）、
 * 子单不存在/归属不符（order.sub_not_found）——均按不存在呈现防越权与
 * 存在性泄露。
 * <p>
 * 依赖装配：五仓储全 mock（查询用例无机械层依赖）；被测用例每用例前
 * 重建（mock 注入后于实例构造——PaymentUseCaseUnitTest 装配先例）。
 */
@ExtendWith(MockitoExtension.class)
class BuyerOrderQueryUnitTest {

    @Mock
    private MasterOrderRepository masterOrderRepository;

    @Mock
    private SubOrderRepository subOrderRepository;

    @Mock
    private WaybillRepository waybillRepository;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private ShopRepository shopRepository;

    /**
     * 被测查询用例（mock 注入后于实例构造）。
     */
    private BuyerOrderQuery query;

    /**
     * 每用例前重建被测用例（依赖均为 mock，无状态跨用例残留）。
     */
    @BeforeEach
    void setUp() {
        query = new BuyerOrderQuery(masterOrderRepository, subOrderRepository,
                waybillRepository, paymentOrderRepository, shopRepository);
    }

    /**
     * 买家基线（买家 1000；创建时刻取「now 相对窗口」——禁止绝对日期
     * 魔法值，时间断言一律相对）。
     */
    private static final Long BUYER = 1000L;

    /**
     * 地址快照基线（六段必填，装配合法实体用）。
     */
    private static final AddressSnapshot ADDRESS = new AddressSnapshot(
            "张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号");

    /**
     * 装配合法主单（金额快照自洽：商品 5000 + 运费 300 = 实付 5300 分）。
     */
    private static MasterOrder master(Long id, MasterOrderStatus status, LocalDateTime createdAt) {
        return new MasterOrder(id, "ORD" + id, BUYER,
                ADDRESS,
                new AmountDetail(5000L, 300L, 0L, 5300L),
                List.of(10L), null, status, createdAt);
    }

    /**
     * 装配合法子单（tenant=shopId；条目小计合计 = 商品总额）。
     */
    private static SubOrder subOrder(Long id, MasterOrder master, Long shopId,
                                     SubOrderStatus status, Long waybillId) {
        final OrderItem item = new OrderItem(900L, 800L, "测试商品", 5000L, 1, 5000L,
                "http://img.example/cover.jpg", "颜色:黑,尺码:M",
                Map.of("颜色", "黑"), Map.of("产地", "中国"));
        return new SubOrder(id, master.getId(), shopId, "SUB" + id,
                ADDRESS,
                new AmountDetail(5000L, 300L, 0L, 5300L),
                List.of(item), status, waybillId);
    }

    /**
     * 装配合法店铺。
     */
    private static Shop shop(Long id, String name) {
        return new Shop(id, name, "http://img.example/logo.png", "测试店铺", ShopStatus.NORMAL);
    }

    /**
     * 装配合法运单（末条轨迹状态 = 当前状态，构造守卫）。
     */
    private static Waybill waybill(Long id, Long subOrderId) {
        final LocalDateTime occurred = LocalDateTime.now().minusMinutes(10);
        final WaybillTrack track = new WaybillTrack(1L, id, WaybillStatus.SHIPPED,
                occurred, "包裹已揽收");
        return new Waybill(id, subOrderId, "测试物流", "SF000000001",
                WaybillStatus.SHIPPED, List.of(track));
    }

    /* ------------------------------------------------------------------ */
    /* happy path                                                         */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("happy-1 列表主流程：分页透传 + 状态过滤 + 子单/店铺名/金额装配")
    void listPaged_happyPath_assemblesViews() {
        final LocalDateTime createdAt = LocalDateTime.now().minusMinutes(30);
        final MasterOrder order1 = master(100L, MasterOrderStatus.PAID, createdAt);
        final MasterOrder order2 = master(101L, MasterOrderStatus.COMPLETED, createdAt.minusDays(1));
        when(masterOrderRepository.listPagedByBuyer(eq(BUYER), any(), eq(0), eq(10)))
                .thenReturn(List.of(order1, order2));
        when(masterOrderRepository.countByBuyer(eq(BUYER), any())).thenReturn(2L);
        when(subOrderRepository.getByMasterOrderId(100L)).thenReturn(
                List.of(subOrder(10L, order1, 5001L, SubOrderStatus.PAID, null)));
        when(subOrderRepository.getByMasterOrderId(101L)).thenReturn(
                List.of(subOrder(11L, order2, 5002L, SubOrderStatus.COMPLETED, null)));
        when(shopRepository.getByID(5001L)).thenReturn(shop(5001L, "店铺甲"));
        when(shopRepository.getByID(5002L)).thenReturn(shop(5002L, "店铺乙"));

        final PageResult<OrderView> result =
                query.listPaged(BUYER, MallOrderStatus.PAID, new PageQuery(1, 10));

        assertThat(result.records()).hasSize(2);
        final OrderView first = result.records().get(0);
        assertThat(first.masterOrderId()).isEqualTo(100L);
        assertThat(first.orderNo()).isEqualTo("ORD100");
        assertThat(first.status()).isEqualTo(MallOrderStatus.PAID);
        assertThat(first.createdAt()).isEqualTo(createdAt.toString());
        assertThat(first.address().receiverName()).isNotBlank();
        assertThat(first.goodsAmount()).isEqualTo(5000L);
        assertThat(first.freightAmount()).isEqualTo(300L);
        assertThat(first.discount()).isZero();
        assertThat(first.paidAmount()).isEqualTo(5300L);
        assertThat(first.subOrders()).hasSize(1);
        final var sub = first.subOrders().get(0);
        assertThat(sub.subOrderId()).isEqualTo(10L);
        assertThat(sub.shopId()).isEqualTo(5001L);
        assertThat(sub.shopName()).isEqualTo("店铺甲");
        assertThat(sub.status()).isEqualTo(MallOrderStatus.PAID);
        assertThat(sub.waybillId()).isNull();
        assertThat(sub.items()).hasSize(1);
        assertThat(sub.items().get(0).productName()).isEqualTo("测试商品");
        assertThat(sub.items().get(0).unitPrice()).isEqualTo(5000L);
        assertThat(sub.items().get(0).subtotal()).isEqualTo(5000L);
        assertThat(sub.items().get(0).specSummary()).isEqualTo("颜色:黑,尺码:M");
        assertThat(result.total()).isEqualTo(2L);
        verify(shopRepository).getByID(5001L);
        verify(shopRepository).getByID(5002L);
        verify(masterOrderRepository).countByBuyer(eq(BUYER), any());
    }

    @Test
    @DisplayName("happy-2 状态 tab 映射：PAID→PAID 单值集合透传仓储（仅 tab 值过滤）")
    void listPaged_tabMapping_singleValueFilter() {
        when(masterOrderRepository.listPagedByBuyer(eq(BUYER), any(), eq(0), eq(10)))
                .thenReturn(List.of());
        when(masterOrderRepository.countByBuyer(eq(BUYER), any())).thenReturn(0L);

        query.listPaged(BUYER, MallOrderStatus.PAID, new PageQuery(1, 10));

        verify(masterOrderRepository).listPagedByBuyer(eq(BUYER),
                org.mockito.ArgumentMatchers.<java.util.Collection<MasterOrderStatus>>argThat(
                        statuses -> statuses != null && statuses.size() == 1
                                && statuses.contains(MasterOrderStatus.PAID)),
                eq(0), eq(10));
        verify(masterOrderRepository).countByBuyer(eq(BUYER),
                org.mockito.ArgumentMatchers.<java.util.Collection<MasterOrderStatus>>argThat(
                        statuses -> statuses != null && statuses.size() == 1
                                && statuses.contains(MasterOrderStatus.PAID)));
    }

    @Test
    @DisplayName("happy-3 详情主流程：主单 + 子单 + 店铺名 + 待支付支付单装配")
    void detail_happyPath_assemblesPaymentView() {
        final LocalDateTime createdAt = LocalDateTime.now().minusMinutes(30);
        final MasterOrder order = master(100L, MasterOrderStatus.PENDING_PAYMENT, createdAt);
        when(masterOrderRepository.getByID(100L)).thenReturn(order);
        when(subOrderRepository.getByMasterOrderId(100L)).thenReturn(
                List.of(subOrder(10L, order, 5001L, SubOrderStatus.PENDING_PAYMENT, null)));
        when(shopRepository.getByID(5001L)).thenReturn(shop(5001L, "店铺甲"));
        when(paymentOrderRepository.findByOrderId(100L)).thenReturn(
                new PaymentOrder(700L, "PAY0001", 100L, 5300L, "MOCK",
                        Instant.now().plusSeconds(1800), PaymentOrderStatus.PENDING_PAYMENT,
                        null, List.of()));

        final OrderView view = query.detail(BUYER, 100L);

        assertThat(view.masterOrderId()).isEqualTo(100L);
        assertThat(view.status()).isEqualTo(MallOrderStatus.PENDING_PAYMENT);
        assertThat(view.createdAt()).isEqualTo(createdAt.toString());
        assertThat(view.subOrders()).hasSize(1);
        assertThat(view.subOrders().get(0).shopName()).isEqualTo("店铺甲");
        assertThat(view.payment()).isNotNull();
        assertThat(view.payment().paymentOrderId()).isEqualTo(700L);
        assertThat(view.payment().payNo()).isEqualTo("PAY0001");
        assertThat(view.payment().amount()).isEqualTo(5300L);
        assertThat(view.payment().status()).isEqualTo(com.nona.api.mall.PaymentStatus.PENDING_PAYMENT);
        assertThat(view.payment().timeoutAt()).isNotBlank();
        verify(paymentOrderRepository).findByOrderId(100L);
    }

    @Test
    @DisplayName("happy-4 运单主流程：归属子单 + 运单轨迹 + 商品行/店铺名装配")
    void waybill_happyPath_assemblesTracksAndItems() {
        final LocalDateTime createdAt = LocalDateTime.now().minusMinutes(30);
        final MasterOrder order = master(100L, MasterOrderStatus.SHIPPED, createdAt);
        final SubOrder sub = subOrder(10L, order, 5001L, SubOrderStatus.SHIPPED, 800L);
        when(masterOrderRepository.getByID(100L)).thenReturn(order);
        when(subOrderRepository.getByID(10L)).thenReturn(sub);
        when(waybillRepository.findBySubOrderId(10L)).thenReturn(java.util.Optional.of(
                waybill(800L, 10L)));
        when(shopRepository.getByID(5001L)).thenReturn(shop(5001L, "店铺甲"));

        final WaybillView view = query.waybill(BUYER, 10L);

        assertThat(view.waybillId()).isEqualTo(800L);
        assertThat(view.subOrderId()).isEqualTo(10L);
        assertThat(view.company()).isEqualTo("测试物流");
        assertThat(view.trackingNo()).isEqualTo("SF000000001");
        assertThat(view.status()).isEqualTo(com.nona.api.mall.MallWaybillStatus.SHIPPED);
        assertThat(view.tracks()).hasSize(1);
        assertThat(view.tracks().get(0).status())
                .isEqualTo(com.nona.api.mall.MallWaybillStatus.SHIPPED);
        assertThat(view.tracks().get(0).description()).isEqualTo("包裹已揽收");
        assertThat(view.subOrderNo()).isEqualTo("SUB10");
        assertThat(view.shopId()).isEqualTo(5001L);
        assertThat(view.shopName()).isEqualTo("店铺甲");
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).skuId()).isEqualTo(800L);
    }

    /* ------------------------------------------------------------------ */
    /* critical path                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("critical-1 全部 tab：tab=null → 仓储收到 null 过滤（全量语义）+ 分页归一化透传")
    void listPaged_allTab_passesNullFilter() {
        when(masterOrderRepository.listPagedByBuyer(eq(BUYER), isNull(), eq(100), eq(50)))
                .thenReturn(List.of());
        when(masterOrderRepository.countByBuyer(eq(BUYER), isNull())).thenReturn(0L);

        final PageResult<OrderView> result = query.listPaged(BUYER, null, new PageQuery(3, 50));

        assertThat(result.records()).isEmpty();
        assertThat(result.total()).isZero();
        verify(masterOrderRepository).listPagedByBuyer(eq(BUYER), isNull(), eq(100), eq(50));
        verify(masterOrderRepository).countByBuyer(eq(BUYER), isNull());
    }

    @Test
    @DisplayName("critical-2 空结果页：fail-safe 空列表 + total 0（非 null 分页骨架）")
    void listPaged_emptyPage_returnsEmptySkeleton() {
        when(masterOrderRepository.listPagedByBuyer(eq(BUYER), isNull(), eq(0), eq(10)))
                .thenReturn(List.of());
        when(masterOrderRepository.countByBuyer(eq(BUYER), isNull())).thenReturn(0L);

        final PageResult<OrderView> result = query.listPaged(BUYER, null, new PageQuery(1, 10));

        assertThat(result.records()).isEmpty();
        assertThat(result.total()).isZero();
        assertThat(result.pageNum()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("critical-3 非待支付主单详情：payment=null，不装配支付单（verify never）")
    void detail_nonPendingPayment_paymentNull() {
        final MasterOrder order = master(101L, MasterOrderStatus.PAID,
                LocalDateTime.now().minusMinutes(30));
        when(masterOrderRepository.getByID(101L)).thenReturn(order);
        when(subOrderRepository.getByMasterOrderId(101L)).thenReturn(
                List.of(subOrder(11L, order, 5002L, SubOrderStatus.PAID, null)));
        when(shopRepository.getByID(5002L)).thenReturn(shop(5002L, "店铺乙"));

        final OrderView view = query.detail(BUYER, 101L);

        assertThat(view.payment()).isNull();
        verify(paymentOrderRepository, never()).findByOrderId(anyLong());
    }

    @Test
    @DisplayName("critical-4 运单不存在：子单合法但无运单 → logistics.not_found 404")
    void waybill_noWaybill_throwsLogisticsNotFound() {
        final MasterOrder order = master(100L, MasterOrderStatus.PAID,
                LocalDateTime.now().minusMinutes(30));
        when(masterOrderRepository.getByID(100L)).thenReturn(order);
        when(subOrderRepository.getByID(10L)).thenReturn(
                subOrder(10L, order, 5001L, SubOrderStatus.PAID, null));
        when(waybillRepository.findBySubOrderId(10L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> query.waybill(BUYER, 10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(EcommerceBusinessCode.LOGISTICS_NOT_FOUND.code());
    }

    @Test
    @DisplayName("critical-5 createdAt 装载回传：满载构造传入值原样出视图 + 实体 getter 只读")
    void detail_createdAt_loadedValueRoundTrip() {
        final LocalDateTime createdAt = LocalDateTime.now().minusHours(2);
        final MasterOrder order = master(102L, MasterOrderStatus.COMPLETED, createdAt);
        when(masterOrderRepository.getByID(102L)).thenReturn(order);
        when(subOrderRepository.getByMasterOrderId(102L)).thenReturn(
                List.of(subOrder(12L, order, 5003L, SubOrderStatus.COMPLETED, null)));
        when(shopRepository.getByID(5003L)).thenReturn(shop(5003L, "店铺丙"));

        final OrderView view = query.detail(BUYER, 102L);

        assertThat(order.getCreatedAt()).isEqualTo(createdAt);
        assertThat(view.createdAt()).isEqualTo(createdAt.toString());
    }

    @Test
    @DisplayName("critical-6 派生状态透传：PARTIALLY_SHIPPED 主单视图呈现（不进 tab 但视图可见）")
    void detail_partiallyShipped_statusTransparent() {
        final MasterOrder order = master(103L, MasterOrderStatus.PARTIALLY_SHIPPED,
                LocalDateTime.now().minusMinutes(5));
        when(masterOrderRepository.getByID(103L)).thenReturn(order);
        when(subOrderRepository.getByMasterOrderId(103L)).thenReturn(
                List.of(subOrder(13L, order, 5001L, SubOrderStatus.SHIPPED, 900L)));
        when(shopRepository.getByID(5001L)).thenReturn(shop(5001L, "店铺甲"));

        final OrderView view = query.detail(BUYER, 103L);

        assertThat(view.status()).isEqualTo(MallOrderStatus.PARTIALLY_SHIPPED);
        assertThat(view.subOrders().get(0).status()).isEqualTo(MallOrderStatus.SHIPPED);
    }

    /* ------------------------------------------------------------------ */
    /* fail path                                                          */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("fail-1 详情主单不存在：getByID null → order.master_not_found 404（无子单装载）")
    void detail_masterMissing_throwsMasterNotFound() {
        when(masterOrderRepository.getByID(999L)).thenReturn(null);

        assertThatThrownBy(() -> query.detail(BUYER, 999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code());

        verify(subOrderRepository, never()).getByMasterOrderId(anyLong());
    }

    @Test
    @DisplayName("fail-2 详情归属不符：他人订单按不存在呈现（同码 404，防越权泄露）")
    void detail_buyerMismatch_throwsMasterNotFound() {
        final MasterOrder otherBuyerOrder = new MasterOrder(200L, "ORD200", 999L,
                ADDRESS,
                new AmountDetail(5000L, 300L, 0L, 5300L),
                List.of(20L), null, MasterOrderStatus.PAID,
                LocalDateTime.now().minusMinutes(30));
        when(masterOrderRepository.getByID(200L)).thenReturn(otherBuyerOrder);

        assertThatThrownBy(() -> query.detail(BUYER, 200L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code());
    }

    @Test
    @DisplayName("fail-3 运单子单不存在：getByID null → order.sub_not_found 404")
    void waybill_subOrderMissing_throwsSubNotFound() {
        when(subOrderRepository.getByID(999L)).thenReturn(null);

        assertThatThrownBy(() -> query.waybill(BUYER, 999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code());

        verify(waybillRepository, never()).findBySubOrderId(anyLong());
    }

    @Test
    @DisplayName("fail-4 运单归属不符：他人子单按不存在呈现（order.sub_not_found，不泄露）")
    void waybill_buyerMismatch_throwsSubNotFound() {
        final MasterOrder otherBuyerOrder = new MasterOrder(210L, "ORD210", 999L,
                ADDRESS,
                new AmountDetail(5000L, 300L, 0L, 5300L),
                List.of(21L), null, MasterOrderStatus.PAID,
                LocalDateTime.now().minusMinutes(30));
        final SubOrder otherSub = subOrder(21L, otherBuyerOrder, 5010L,
                SubOrderStatus.PAID, null);
        when(masterOrderRepository.getByID(210L)).thenReturn(otherBuyerOrder);
        when(subOrderRepository.getByID(21L)).thenReturn(otherSub);

        assertThatThrownBy(() -> query.waybill(BUYER, 21L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code());
    }
}