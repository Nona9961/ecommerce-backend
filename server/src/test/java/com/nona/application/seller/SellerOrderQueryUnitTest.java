package com.nona.application.seller;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.seller.SellerSubOrderDetail;
import com.nona.api.seller.SellerSubOrderItem;
import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.domain.logistics.repo.WaybillRepository;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家端本店订单查询用例场景测试（S11 订单列表 / S12 订单详情+运单概要，
 * WU-60 红阶段契约；卖方查询面 = WU-47 约定端点形状）。
 * <p>
 * 覆盖：happy——列表分页（状态多值过滤透传 + 分页参数换算 + 行字段
 * 逐字段锁定）与详情（已发货运单概要 / 地址金额订单项快照字段逐字段
 * 锁定）；critical——statuses null/空 = 全量透传、空页、未发货运单概要
 * null、运单引用悬挂容忍 null、首项主图 null 与多订单项 itemCount；
 * fail——子单不存在与跨店请求（S2.3/G1.1 锚点：A 店铺查询 B 店铺子单）
 * 按不存在呈现 404、仓储参数守卫异常透传。
 * <p>
 * 依赖装配：SubOrderRepository / WaybillRepository 全 mock（@BeforeEach
 * 重建被测用例，mock 桩逐用例布置全部被使用）；时间断言 = fixture
 * createTime 透传投影（LocalDateTime.toString()，FavoriteItem 先例），
 * 零绝对魔法日期。红阶段失败原因 = 实现缺失（用例方法体 UOE），而非
 * 语法/装配错误。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class SellerOrderQueryUnitTest {

    /**
     * 测试店铺 / 主单 / 子单 / 运单
     */
    private static final long SHOP_A = 4001L;
    private static final long SHOP_B = 4002L;
    private static final long MASTER_ID = 100L;
    private static final long SUB_A = 101L;
    private static final long WAYBILL_ID = 9001L;

    /**
     * fixture 下单时间（透传投影断言锚点，非魔法断言值）
     */
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 8, 10, 30, 0);

    @Mock
    private SubOrderRepository subOrderRepository;

    @Mock
    private WaybillRepository waybillRepository;

    /**
     * 被测用例（红阶段不注册 Spring；依赖全 mock，setUp 装配）
     */
    private SellerOrderQuery query;

    /**
     * 每用例前重建被测用例。
     */
    @BeforeEach
    void setUp() {
        query = new SellerOrderQuery(subOrderRepository, waybillRepository);
    }

    /* ================= fixtures ================= */

    /**
     * 已发货子单 A（双订单项：SKU 3001 主图非空 + SKU 3002 主图 null；
     * 金额自洽：15000×2 + 6000×3 = 48000，运费 800、实付 48800）。
     */
    private static SubOrder subShippedA() {
        return new SubOrder(SUB_A, MASTER_ID, SHOP_A, "SO2026090800101",
                address(), amount(48000L, 800L),
                List.of(item(3001L, 15000L, 2, "/files/p3001.png"),
                        item(3002L, 6000L, 3, null)),
                SubOrderStatus.SHIPPED, WAYBILL_ID, CREATED);
    }

    /**
     * 已支付未发货子单 A（单订单项；waybillId null——未发货形态）。
     * 金额自洽：15000×2 = 30000，运费 800、实付 30800。
     */
    private static SubOrder subPaidNoWaybill() {
        return new SubOrder(SUB_A, MASTER_ID, SHOP_A, "SO2026090800101",
                address(), amount(30000L, 800L),
                List.of(item(3001L, 15000L, 2, "/files/p3001.png")),
                SubOrderStatus.PAID, null, CREATED);
    }

    /**
     * 在途运单 fixture（company/trackingNo 定型 + 状态 IN_TRANSIT）。
     */
    private static Waybill waybillInTransit() {
        return new Waybill(WAYBILL_ID, SUB_A, "顺丰速运", "SF202609080001",
                WaybillStatus.IN_TRANSIT,
                List.of(new WaybillTrack(9002L, WAYBILL_ID,
                        WaybillStatus.IN_TRANSIT, LocalDateTime.now(),
                        "运输中")));
    }

    private static OrderItem item(Long skuId, long price, int qty, String image) {
        return new OrderItem(skuId, skuId, "测试商品" + skuId, price, qty, price * qty,
                image, "颜色:黑,尺码:M", Map.of(), Map.of());
    }

    private static AmountDetail amount(long goods, long freight) {
        return new AmountDetail(goods, freight, 0L, goods + freight);
    }

    private static AddressSnapshot address() {
        return new AddressSnapshot("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号");
    }

    /* ================= happy path ================= */

    /**
     * happy-1 列表分页全链路：状态多值过滤透传 + 分页参数换算（第 1 页
     * offset=0）+ count 同口径 + 行字段逐字段锁定（11 字段，金额分）。
     */
    @Test
    @DisplayName("列表分页：过滤透传 + 分页换算 + 行 11 字段逐字段锁定")
    void listPaged_happy_filterAndPageAndRowShape() {
        when(subOrderRepository.listPagedByShop(SHOP_A,
                List.of(SubOrderStatus.REFUNDING, SubOrderStatus.REFUNDED), 0, 10))
                .thenReturn(List.of(subShippedA()));
        when(subOrderRepository.countByShop(SHOP_A,
                List.of(SubOrderStatus.REFUNDING, SubOrderStatus.REFUNDED)))
                .thenReturn(1L);

        final PageResult<SellerSubOrderItem> result = query.listPaged(
                SHOP_A, List.of(SubOrderStatus.REFUNDING, SubOrderStatus.REFUNDED),
                new PageQuery(1, 10));

        verify(subOrderRepository).listPagedByShop(eq(SHOP_A),
                eq(List.of(SubOrderStatus.REFUNDING, SubOrderStatus.REFUNDED)), eq(0), eq(10));
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.pageNum()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.records()).hasSize(1);
        final SellerSubOrderItem row = result.records().get(0);
        // 11 字段逐字段锁定（前端 SellerSubOrderItemWire 形状）
        assertThat(row.subOrderId()).isEqualTo(SUB_A);
        assertThat(row.subOrderNo()).isEqualTo("SO2026090800101");
        assertThat(row.masterOrderId()).isEqualTo(MASTER_ID);
        assertThat(row.status()).isEqualTo("SHIPPED");
        assertThat(row.recipient()).isEqualTo("张三");
        assertThat(row.goodsAmount()).isEqualTo(48000L);
        assertThat(row.freightAmount()).isEqualTo(800L);
        assertThat(row.paidAmount()).isEqualTo(48800L);
        assertThat(row.itemCount()).isEqualTo(2);
        assertThat(row.firstImageUrl()).isEqualTo("/files/p3001.png");
        assertThat(row.createTime()).isEqualTo(CREATED.toString());
    }

    /**
     * happy-2 详情全链路（已发货）：地址/金额/订单项/运单概要字段逐字段
     * 锁定（10 字段 + 内嵌三视图逐字段）。
     */
    @Test
    @DisplayName("详情（已发货）：地址/金额/订单项/运单概要 字段逐字段锁定")
    void detail_happy_shippedWaybillSummary() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subShippedA());
        when(waybillRepository.getByID(WAYBILL_ID)).thenReturn(waybillInTransit());

        final SellerSubOrderDetail detail = query.detail(SHOP_A, SUB_A);

        assertThat(detail.subOrderId()).isEqualTo(SUB_A);
        assertThat(detail.subOrderNo()).isEqualTo("SO2026090800101");
        assertThat(detail.masterOrderId()).isEqualTo(MASTER_ID);
        assertThat(detail.status()).isEqualTo("SHIPPED");
        assertThat(detail.shopId()).isEqualTo(SHOP_A);
        assertThat(detail.address().recipient()).isEqualTo("张三");
        assertThat(detail.address().phone()).isEqualTo("13800000000");
        assertThat(detail.address().province()).isEqualTo("浙江省");
        assertThat(detail.address().city()).isEqualTo("杭州市");
        assertThat(detail.address().district()).isEqualTo("西湖区");
        assertThat(detail.address().detail()).isEqualTo("文一西路 1 号");
        assertThat(detail.amount().goodsAmount()).isEqualTo(48000L);
        assertThat(detail.amount().freightAmount()).isEqualTo(800L);
        assertThat(detail.amount().discount()).isEqualTo(0L);
        assertThat(detail.amount().paidAmount()).isEqualTo(48800L);
        assertThat(detail.items()).hasSize(2);
        final var first = detail.items().get(0);
        assertThat(first.productId()).isEqualTo(3001L);
        assertThat(first.skuId()).isEqualTo(3001L);
        assertThat(first.productName()).isEqualTo("测试商品3001");
        assertThat(first.unitPrice()).isEqualTo(15000L);
        assertThat(first.quantity()).isEqualTo(2);
        assertThat(first.subtotal()).isEqualTo(30000L);
        assertThat(first.mainImageUrl()).isEqualTo("/files/p3001.png");
        assertThat(first.specSummary()).isEqualTo("颜色:黑,尺码:M");
        assertThat(detail.waybill()).isNotNull();
        assertThat(detail.waybill().company()).isEqualTo("顺丰速运");
        assertThat(detail.waybill().trackingNo()).isEqualTo("SF202609080001");
        assertThat(detail.waybill().status()).isEqualTo("IN_TRANSIT");
        assertThat(detail.createTime()).isEqualTo(CREATED.toString());
    }

    /**
     * happy-3 分页参数换算：第 3 页 × 20 → offset=40 limit=20（PageQuery.
     * offset() 承载，仓储契约消费）。
     */
    @Test
    @DisplayName("列表分页：第 3 页 × 20 → offset=40 limit=20")
    void listPaged_offsetConversion_page3Size20() {
        when(subOrderRepository.listPagedByShop(SHOP_A, null, 40, 20))
                .thenReturn(List.of());
        when(subOrderRepository.countByShop(SHOP_A, null)).thenReturn(0L);

        final PageResult<SellerSubOrderItem> result =
                query.listPaged(SHOP_A, null, new PageQuery(3, 20));

        verify(subOrderRepository).listPagedByShop(SHOP_A, null, 40, 20);
        assertThat(result.records()).isEmpty();
        assertThat(result.total()).isZero();
    }

    /* ================= critical path ================= */

    /**
     * critical-1 statuses 空集合 = 全量透传（前端「全部」tab 不传 status
     * → 绑定空集合，与 null 同语义）。
     */
    @Test
    @DisplayName("列表分页：statuses 空集合 = 全量透传")
    void listPaged_emptyStatusesIsAll() {
        when(subOrderRepository.listPagedByShop(SHOP_A, List.of(), 0, 10))
                .thenReturn(List.of());
        when(subOrderRepository.countByShop(SHOP_A, List.of())).thenReturn(0L);

        final PageResult<SellerSubOrderItem> result =
                query.listPaged(SHOP_A, List.of(), new PageQuery(1, 10));

        verify(subOrderRepository).listPagedByShop(SHOP_A, List.of(), 0, 10);
        assertThat(result.records()).isEmpty();
    }

    /**
     * critical-2 空页呈现：空列表 + total 0（PageResult 防御空列表非 null）。
     */
    @Test
    @DisplayName("列表分页：空页 = 空列表非 null + total 0")
    void listPaged_emptyPage() {
        when(subOrderRepository.listPagedByShop(SHOP_A, null, 0, 10))
                .thenReturn(List.of());
        when(subOrderRepository.countByShop(SHOP_A, null)).thenReturn(0L);

        final PageResult<SellerSubOrderItem> result =
                query.listPaged(SHOP_A, null, new PageQuery(1, 10));

        assertThat(result.records()).isNotNull().isEmpty();
        assertThat(result.total()).isZero();
    }

    /**
     * critical-3 未发货详情：waybillId 引用空 → 运单概要 null（前端
     * waybill 可空契约），不查运单仓储。
     */
    @Test
    @DisplayName("详情（未发货）：运单概要 null，不触达运单仓储")
    void detail_notShipped_waybillNull() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subPaidNoWaybill());

        final SellerSubOrderDetail detail = query.detail(SHOP_A, SUB_A);

        assertThat(detail.waybill()).isNull();
        assertThat(detail.status()).isEqualTo("PAID");
        verify(waybillRepository, never()).getByID(anyLong());
    }

    /**
     * critical-4 运单引用悬挂（已发货但运单行缺失 = 数据异常容忍面）：
     * waybill 概要按 null 呈现，不阻断详情读取（展示驱动）。
     */
    @Test
    @DisplayName("详情：运单引用悬挂 → 概要 null 容忍呈现（数据异常防御面）")
    void detail_hangingWaybill_waybillNull() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subShippedA());
        when(waybillRepository.getByID(WAYBILL_ID)).thenReturn(null);

        final SellerSubOrderDetail detail = query.detail(SHOP_A, SUB_A);

        assertThat(detail.waybill()).isNull();
        assertThat(detail.status()).isEqualTo("SHIPPED");
    }

    /**
     * critical-5 首项主图为空与多订单项：firstImageUrl null + itemCount 计数。
     */
    @Test
    @DisplayName("列表行派生：首项主图 null + itemCount = 订单项数")
    void listPaged_firstImageNullAndItemCount() {
        final SubOrder twoItems = new SubOrder(SUB_A, MASTER_ID, SHOP_A, "SO1",
                address(), amount(48000L, 800L),
                List.of(item(3001L, 15000L, 2, null),
                        item(3002L, 6000L, 3, "/files/p3002.png")),
                SubOrderStatus.COMPLETED, null, CREATED);
        when(subOrderRepository.listPagedByShop(SHOP_A, null, 0, 10))
                .thenReturn(List.of(twoItems));
        when(subOrderRepository.countByShop(SHOP_A, null)).thenReturn(1L);

        final SellerSubOrderItem row =
                query.listPaged(SHOP_A, null, new PageQuery(1, 10)).records().get(0);

        assertThat(row.firstImageUrl()).isNull();
        assertThat(row.itemCount()).isEqualTo(2);
    }

    /* ================= fail path ================= */

    /**
     * fail-1 子单不存在：按不存在呈现 404（ORDER_SUB_NOT_FOUND 业务码）。
     */
    @Test
    @DisplayName("详情：子单不存在 → 404（order.sub_not_found）")
    void detail_subOrderMissing_404() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(null);

        assertThatThrownBy(() -> query.detail(SHOP_A, SUB_A))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode(),
                        e -> ((BusinessException) e).getHttpStatus())
                .containsExactly(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(), 404);
        verify(waybillRepository, never()).getByID(anyLong());
    }

    /**
     * fail-2 跨店 fail-closed（S2.3/G1.1 锚点）：B 店铺查询 A 店铺子单 →
     * 404 按不存在呈现（归属不泄露；显式归属二道校验——租户过滤兜底先行）。
     */
    @Test
    @DisplayName("跨店 fail-closed：B 店铺查询 A 店铺子单 → 404（S2.3/G1.1 锚点）")
    void detail_crossShop_404() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subShippedA());

        assertThatThrownBy(() -> query.detail(SHOP_B, SUB_A))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode(),
                        e -> ((BusinessException) e).getHttpStatus())
                .containsExactly(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(), 404);
        verify(waybillRepository, never()).getByID(anyLong());
    }

    /**
     * fail-3 仓储参数守卫透传：非法分页参数由仓储契约拒绝（55 冻结
     * offset&lt;0/limit&lt;=0 抛 IAE），用例层透传不吞没。
     */
    @Test
    @DisplayName("列表分页：仓储参数守卫异常透传（fail-closed 契约层承载）")
    void listPaged_repoGuardPropagated() {
        when(subOrderRepository.listPagedByShop(anyLong(), nullable(Collection.class), anyInt(), anyInt()))
                .thenThrow(new IllegalArgumentException("offset 不能为负"));

        assertThatThrownBy(() -> query.listPaged(SHOP_A, null, new PageQuery(1, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
    }
}