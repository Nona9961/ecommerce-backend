package com.nona.domain.inventory.service;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.ports.InventoryAvailable;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 库存门面实现单元测试：queryAvailable 查询面（多 SKU 可售量聚合——
 * 缺行按 0 / 去重 / 空入参 / 跨店铺缺行统一 0 呈现）。
 * <p>
 * 用 mock 仓储隔离持久化：缺行语义（仓储返回 null）跨店铺与未初始化
 * 等价（fail-closed 统一按 0 呈现，调用方无需区分缺失与零——zip 语义）。
 * 动作面（preoccupy/confirmDeduct/rollback/adjust）签名冻结、门面接线
 * 未落地——UOE 守卫由接口契约承载，本类不重复断言实现。
 *
 * @author nona9961
 */
class InventoryFacadeImplTest {

    /**
     * 被测门面实现
     */
    private InventoryFacadeImpl facade;

    /**
     * 库存仓储（mock）
     */
    private InventoryItemRepository inventoryItemRepository;

    /**
     * 每用例前：装配 mock 仓储与门面实现。
     */
    @BeforeEach
    void setUp() {
        inventoryItemRepository = mock(InventoryItemRepository.class);
        facade = new InventoryFacadeImpl(inventoryItemRepository);
    }

    /**
     * happy：多 SKU 可售量查询——全量返回（zip 语义），已有库存返回
     * 可售值，缺行按 0。
     */
    @Test
    @DisplayName("多SKU可售量全量返回缺行按0")
    void queryAvailable_returnsAllWithZeroForMissing() {
        final InventoryItem existing = new InventoryItem(1L, 9101L, 88001L, 7, 3, 0, 1);
        when(inventoryItemRepository.getBySkuId(88001L)).thenReturn(existing);
        when(inventoryItemRepository.getBySkuId(88002L)).thenReturn(null);

        final List<InventoryAvailable> result = facade.queryAvailable(List.of(88001L, 88002L));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).skuId()).isEqualTo(88001L);
        assertThat(result.get(0).available()).isEqualTo(7);
        assertThat(result.get(1).skuId()).isEqualTo(88002L);
        assertThat(result.get(1).available()).isZero();
    }

    /**
     * critical：请求 SKU 去重——重复 SKU 仅返回一次（实现保证去重
     * 语义）。
     */
    @Test
    @DisplayName("请求SKU去重返回")
    void queryAvailable_deduplicatesRequest() {
        final InventoryItem existing = new InventoryItem(1L, 9101L, 88001L, 4, 0, 0, 0);
        when(inventoryItemRepository.getBySkuId(88001L)).thenReturn(existing);

        final List<InventoryAvailable> result = facade.queryAvailable(List.of(88001L, 88001L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).skuId()).isEqualTo(88001L);
        assertThat(result.get(0).available()).isEqualTo(4);
    }

    /**
     * critical：空入参返回空列表（null 与空集合均安全）。
     */
    @Test
    @DisplayName("空入参返回空")
    void queryAvailable_emptyInputReturnsEmpty() {
        assertThat(facade.queryAvailable(null)).isEmpty();
        assertThat(facade.queryAvailable(List.of())).isEmpty();
    }

    /**
     * critical：跨店铺缺行统一按 0 呈现（fail-closed——仓储租户过滤后
     * 返回 null，不泄露归属；买家视角不可售）。
     */
    @Test
    @DisplayName("缺行按0呈现不泄露归属")
    void queryAvailable_missingAsZero() {
        when(inventoryItemRepository.getBySkuId(88002L)).thenReturn(null);

        final List<InventoryAvailable> result = facade.queryAvailable(List.of(88002L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).available()).isZero();
    }
}