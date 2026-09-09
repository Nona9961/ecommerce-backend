package com.nona.domain.inventory.service;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.ports.InventoryAvailable;
import com.nona.domain.inventory.ports.StockChangeItem;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存门面实现单元测试：查询面（多 SKU 可售量聚合——缺行按 0 / 去重 /
 * 空入参 / 跨店铺缺行统一 0 呈现）与订单驱动动作面委托（preoccupy/
 * confirmDeduct/rollback/restore——逐 item 透传领域服务，参数不加工、
 * 异常不吞；null/空明细按无操作安全返回）。
 * <p>
 * 用 mock 仓储与 mock 领域服务隔离持久化：缺行语义（仓储返回 null）跨
 * 店铺与未初始化等价（fail-closed 统一按 0 呈现，调用方无需区分缺失与
 * 零——zip 语义）。动作面方法级契约（幂等键/条件更新/流水/事件）由领域
 * 服务承载（其自身集成面单测覆盖），此处只锁门面委托形态；手工调整
 * （adjust）保持契约冻结占位——UOE 守卫由接口契约承载，本类不重复断言。
 *
 * @author nona9961
 */
class InventoryFacadeImplUnitTest {

    /**
     * 被测门面实现
     */
    private InventoryFacadeImpl facade;

    /**
     * 库存仓储（mock）
     */
    private InventoryItemRepository inventoryItemRepository;

    /**
     * 库存保留领域服务（mock——动作面委托目标）
     */
    private InventoryReservationService inventoryReservationService;

    /**
     * 每用例前：装配 mock 仓储、mock 领域服务与门面实现。
     */
    @BeforeEach
    void setUp() {
        inventoryItemRepository = mock(InventoryItemRepository.class);
        inventoryReservationService = mock(InventoryReservationService.class);
        facade = new InventoryFacadeImpl(inventoryItemRepository, inventoryReservationService);
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

    /* ================= 动作面委托（preoccupy/confirmDeduct/rollback/restore） ================= */

    /**
     * happy：预占逐 item 委托领域服务——明细拆解为 (orderId, skuId,
     * quantity) 透传，参数不加工（领域服务内完成幂等判定/条件更新）。
     */
    @Test
    @DisplayName("预占：逐 item 委托领域服务（参数透传）")
    void preoccupy_delegatesPerItem() {
        facade.preoccupy(100L, List.of(
                new StockChangeItem(88001L, 2), new StockChangeItem(88002L, 3)));

        verify(inventoryReservationService).preoccupy(100L, 88001L, 2);
        verify(inventoryReservationService).preoccupy(100L, 88002L, 3);
    }

    /**
     * happy：确认扣减逐 item 委托领域服务（held 防超预占语义在领域
     * 服务内承载）。
     */
    @Test
    @DisplayName("确认扣减：逐 item 委托领域服务（参数透传）")
    void confirmDeduct_delegatesPerItem() {
        facade.confirmDeduct(100L, List.of(new StockChangeItem(88001L, 2)));

        verify(inventoryReservationService).confirmDeduct(100L, 88001L, 2);
    }

    /**
     * happy：预占回滚逐 item 委托领域服务（释放语义在领域服务内承载）。
     */
    @Test
    @DisplayName("预占回滚：逐 item 委托领域服务（参数透传）")
    void rollback_delegatesPerItem() {
        facade.rollback(100L, List.of(new StockChangeItem(88001L, 2)));

        verify(inventoryReservationService).rollback(100L, 88001L, 2);
    }

    /**
     * happy：退款回补逐 item 委托领域服务（REFUND_RESTORE 幂等键语义在
     * 领域服务内承载）。
     */
    @Test
    @DisplayName("退款回补：逐 item 委托领域服务（参数透传）")
    void restore_delegatesPerItem() {
        facade.restore(100L, List.of(new StockChangeItem(88001L, 2)));

        verify(inventoryReservationService).restore(100L, 88001L, 2);
    }

    /**
     * critical：null / 空明细按无操作安全返回——四个动作面方法均不触
     * 达领域服务（与查询面 null 防御同构）。
     */
    @Test
    @DisplayName("null与空明细：无操作安全返回")
    void actionMethods_nullOrEmptyItems_noop() {
        facade.preoccupy(100L, null);
        facade.preoccupy(100L, List.of());
        facade.confirmDeduct(100L, null);
        facade.rollback(100L, List.of());
        facade.restore(100L, null);

        verify(inventoryReservationService, never()).preoccupy(anyLong(), anyLong(), anyInt());
        verify(inventoryReservationService, never()).confirmDeduct(anyLong(), anyLong(), anyInt());
        verify(inventoryReservationService, never()).rollback(anyLong(), anyLong(), anyInt());
        verify(inventoryReservationService, never()).restore(anyLong(), anyLong(), anyInt());
    }

    /**
     * fail：领域服务业务异常原样透传（门面不捕获不包装——失败语义由
     * 领域服务承载，编排方事务整体回滚面不变）。
     */
    @Test
    @DisplayName("领域服务业务异常：原样透传")
    void actionMethods_businessExceptionPropagates() {
        final BusinessException boom = new BusinessException("400", "可售库存不足，无法预占");
        when(inventoryReservationService.preoccupy(100L, 88001L, 2)).thenThrow(boom);

        assertThatThrownBy(() -> facade.preoccupy(100L, List.of(new StockChangeItem(88001L, 2))))
                .isSameAs(boom);
    }
}