package com.nona.application.seller;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.seller.InventoryLogView;
import com.nona.api.seller.StockView;
import com.nona.domain.catalog.repo.SkuProductView;
import com.nona.domain.catalog.repo.SkuProductViewRepository;
import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import com.nona.domain.inventory.repo.InventoryLogRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家端库存查询用例场景测试（库存管理契约；卖方
 * 查询面 = 约定端点形状：库存三态分页 + catalog join、单行回显、
 * 流水分页）。
 * <p>
 * 覆盖：happy——分页 join（库存行 → StockView 商品摘要字段装配 + 调用
 * 参数与 join 入参断言）与流水映射（前后三态/操作人/原因/类型枚举名
 * 14 字段逐字段锁定）；critical——空页、join 单元素回显、流水空页；
 * fail——跨店/不存在 SKU 按 404 呈现（归属不泄露）、join 缺失按数据
 * 异常呈现（fail-closed，不静默 null 展示）、仓储守卫异常透传。
 * <p>
 * 依赖装配：InventoryItemRepository / InventoryLogRepository /
 * SkuProductViewRepository 全 mock（@BeforeEach 重建被测用例，mock 桩
 * 逐用例布置全部被使用）；时间断言 = fixture createdAt 透传投影（零
 * 绝对魔法日期）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class SellerStockQueryUnitTest {

    /**
     * 测试店铺 / SKU / 商品
     */
    private static final long SHOP_A = 4001L;
    private static final long SKU_A1 = 3001L;
    private static final long PRODUCT_A1 = 2001L;

    @Mock
    private InventoryItemRepository inventoryItemRepository;

    @Mock
    private InventoryLogRepository inventoryLogRepository;

    @Mock
    private SkuProductViewRepository skuProductViewRepository;

    /**
     * 被测用例（依赖全 mock，setUp 装配）
     */
    private SellerStockQuery query;

    /**
     * 每用例前重建被测用例。
     */
    @BeforeEach
    void setUp() {
        query = new SellerStockQuery(inventoryItemRepository, inventoryLogRepository,
                skuProductViewRepository);
    }

    /* ================= fixtures ================= */

    /**
     * 库存行 fixture（三态：可售 15 / 预占 5 / 已售 30，版本 7）。
     */
    private static InventoryItem itemA1() {
        return new InventoryItem(5001L, SHOP_A, SKU_A1, 15, 5, 30, 7);
    }

    /**
     * SKU 商品摘要 fixture（catalog join 投影：商品名/规格摘要）。
     */
    private static SkuProductView viewA1() {
        return new SkuProductView(SKU_A1, PRODUCT_A1, "经典款 T 恤", "颜色:黑,尺码:M");
    }

    /**
     * 流水行 fixture（MANUAL_ADJUST 型：+5 可售，操作人/原因齐备；
     * createdAt 持久化读回值）。
     */
    private static InventoryLog logManual() {
        return new InventoryLog(6001L, SHOP_A, SKU_A1, InventoryLogType.MANUAL_ADJUST,
                5, null, 10, 5, 30, 15, 5, 30, "1001", "补货",
                LocalDateTime.of(2026, 9, 8, 11, 0, 0));
    }

    /* ================= happy path ================= */

    /**
     * happy-1 库存分页 + catalog join：仓储两查（listPaged/count）+ 投影
     * 批量 join（skuIds 集合入参）+ 行 7 字段逐字段锁定（金额面无——
     * 库存为整数件）。
     */
    @Test
    @DisplayName("库存分页：两查 + join 入参 + 行 7 字段逐字段锁定")
    void listPaged_happy_joinAndRowShape() {
        when(inventoryItemRepository.listPaged(0, 10)).thenReturn(List.of(itemA1()));
        when(inventoryItemRepository.count()).thenReturn(1L);
        when(skuProductViewRepository.listBySkuIds(Set.of(SKU_A1)))
                .thenReturn(List.of(viewA1()));

        final PageResult<StockView> result = query.listPaged(SHOP_A, new PageQuery(1, 10));

        verify(inventoryItemRepository).listPaged(0, 10);
        verify(skuProductViewRepository).listBySkuIds(Set.of(SKU_A1));
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.records()).hasSize(1);
        final StockView row = result.records().get(0);
        assertThat(row.skuId()).isEqualTo(SKU_A1);
        assertThat(row.productId()).isEqualTo(PRODUCT_A1);
        assertThat(row.productName()).isEqualTo("经典款 T 恤");
        assertThat(row.specSummary()).isEqualTo("颜色:黑,尺码:M");
        assertThat(row.available()).isEqualTo(15);
        assertThat(row.held()).isEqualTo(5);
        assertThat(row.sold()).isEqualTo(30);
    }

    /**
     * happy-2 单行回显（调整响应组装）：库存聚合 → StockView（join
     * 单元素批量装配同一投影口径）。
     */
    @Test
    @DisplayName("单行回显：库存聚合 + join 单元素 → StockView")
    void toView_happy_singleRow() {
        when(skuProductViewRepository.listBySkuIds(Set.of(SKU_A1)))
                .thenReturn(List.of(viewA1()));

        final StockView view = query.toView(SHOP_A, itemA1());

        assertThat(view.skuId()).isEqualTo(SKU_A1);
        assertThat(view.productId()).isEqualTo(PRODUCT_A1);
        assertThat(view.productName()).isEqualTo("经典款 T 恤");
        assertThat(view.available()).isEqualTo(15);
        assertThat(view.held()).isEqualTo(5);
        assertThat(view.sold()).isEqualTo(30);
    }

    /**
     * happy-3 流水分页：SKU 定位守卫通过 → 两查（listBySkuPaged/countBySku）
     * + 行 14 字段逐字段锁定（类型枚举名/前后三态/操作人/原因/createTime
     * 投影）。
     */
    @Test
    @DisplayName("流水分页：定位守卫 + 两查 + 行 14 字段逐字段锁定")
    void listLogs_happy_fullRowShape() {
        when(inventoryItemRepository.getBySkuId(SKU_A1)).thenReturn(itemA1());
        when(inventoryLogRepository.listBySkuPaged(SKU_A1, 0, 10))
                .thenReturn(List.of(logManual()));
        when(inventoryLogRepository.countBySku(SKU_A1)).thenReturn(1L);

        final PageResult<InventoryLogView> result = query.listLogs(SHOP_A, SKU_A1,
                new PageQuery(1, 10));

        verify(inventoryLogRepository).listBySkuPaged(SKU_A1, 0, 10);
        assertThat(result.total()).isEqualTo(1L);
        final InventoryLogView row = result.records().get(0);
        assertThat(row.id()).isEqualTo(6001L);
        assertThat(row.skuId()).isEqualTo(SKU_A1);
        assertThat(row.type()).isEqualTo("MANUAL_ADJUST");
        assertThat(row.delta()).isEqualTo(5);
        assertThat(row.orderId()).isNull();
        assertThat(row.beforeAvailable()).isEqualTo(10);
        assertThat(row.beforeHeld()).isEqualTo(5);
        assertThat(row.beforeSold()).isEqualTo(30);
        assertThat(row.afterAvailable()).isEqualTo(15);
        assertThat(row.afterHeld()).isEqualTo(5);
        assertThat(row.afterSold()).isEqualTo(30);
        assertThat(row.operator()).isEqualTo("1001");
        assertThat(row.reason()).isEqualTo("补货");
        assertThat(row.createdAt()).isEqualTo("2026-09-08T11:00");
    }

    /* ================= critical path ================= */

    /**
     * critical-1 库存分页空页：空列表 + total 0（空列表非 null）。
     */
    @Test
    @DisplayName("库存分页：空页 = 空列表非 null + total 0（join 不触达）")
    void listPaged_emptyPage() {
        when(inventoryItemRepository.listPaged(0, 10)).thenReturn(List.of());
        when(inventoryItemRepository.count()).thenReturn(0L);

        final PageResult<StockView> result = query.listPaged(SHOP_A, new PageQuery(1, 10));

        assertThat(result.records()).isNotNull().isEmpty();
        assertThat(result.total()).isZero();
        verify(skuProductViewRepository, never()).listBySkuIds(anyCollection());
    }

    /**
     * critical-2 流水分页空页：SKU 存在但无流水 = 空列表 + total 0。
     */
    @Test
    @DisplayName("流水分页：无流水 = 空列表 + total 0")
    void listLogs_emptyLogs() {
        when(inventoryItemRepository.getBySkuId(SKU_A1)).thenReturn(itemA1());
        when(inventoryLogRepository.listBySkuPaged(SKU_A1, 0, 10))
                .thenReturn(List.of());
        when(inventoryLogRepository.countBySku(SKU_A1)).thenReturn(0L);

        final PageResult<InventoryLogView> result =
                query.listLogs(SHOP_A, SKU_A1, new PageQuery(1, 10));

        assertThat(result.records()).isNotNull().isEmpty();
        assertThat(result.total()).isZero();
    }

    /**
     * critical-3 分页参数换算：第 2 页 × 10 → offset=10 limit=10。
     */
    @Test
    @DisplayName("库存分页：第 2 页 × 10 → offset=10 limit=10")
    void listPaged_offsetConversion_page2Size10() {
        when(inventoryItemRepository.listPaged(10, 10)).thenReturn(List.of());
        when(inventoryItemRepository.count()).thenReturn(0L);

        query.listPaged(SHOP_A, new PageQuery(2, 10));

        verify(inventoryItemRepository).listPaged(10, 10);
    }

    /* ================= fail path ================= */

    /**
     * fail-1 流水分页 SKU 不存在/跨店：定位守卫 404（inventory.not_found，
     * 归属不泄露——租户过滤 fail-closed 先行）。
     */
    @Test
    @DisplayName("流水分页：SKU 不存在/跨店 → 404（inventory.not_found）")
    void listLogs_skuMissing_404() {
        when(inventoryItemRepository.getBySkuId(SKU_A1)).thenReturn(null);

        assertThatThrownBy(() -> query.listLogs(SHOP_A, SKU_A1, new PageQuery(1, 10)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode(),
                        e -> ((BusinessException) e).getHttpStatus())
                .containsExactly(EcommerceBusinessCode.INVENTORY_NOT_FOUND.code(), 404);
        verify(inventoryLogRepository, never()).listBySkuPaged(anyLong(), anyInt(), anyInt());
    }

    /**
     * fail-2 join 缺失（库存行存在但 SKU 投影不命中 = 装配数据异常）：
     * 按数据异常呈现（fail-closed，不静默降级 null 展示——前端类型
     * productId/productName 非空契约）。
     */
    @Test
    @DisplayName("库存分页：join 缺失 → 数据异常呈现（fail-closed）")
    void listPaged_joinMissing_dataException() {
        when(inventoryItemRepository.listPaged(0, 10)).thenReturn(List.of(itemA1()));
        when(inventoryItemRepository.count()).thenReturn(1L);
        when(skuProductViewRepository.listBySkuIds(Set.of(SKU_A1)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> query.listPaged(SHOP_A, new PageQuery(1, 10)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.CATALOG_NOT_FOUND.code());
    }

    /**
     * fail-3 仓储参数守卫透传：非法分页参数由仓储契约拒绝（55 冻结
     * offset&lt;0/limit&lt;=0 抛 IAE），用例层透传不吞没。
     */
    @Test
    @DisplayName("库存分页：仓储参数守卫异常透传")
    void listPaged_repoGuardPropagated() {
        when(inventoryItemRepository.listPaged(anyInt(), anyInt()))
                .thenThrow(new IllegalArgumentException("limit 必须为正"));

        assertThatThrownBy(() -> query.listPaged(SHOP_A, new PageQuery(1, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }
}