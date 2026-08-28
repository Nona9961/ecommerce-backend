package com.nona.inf.persistence.reconstructor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import com.nona.annotation.ScaffoldGenerated;

/**
 * ReconstructedPos 单元测试
 */
@DisplayName("ReconstructedPos 测试")
@ScaffoldGenerated
class ReconstructedPosTest {

    static class OrderPO {
        private Long id;
    }

    static class OrderItemPO {
        private Long id;
    }

    static class CustomerPO {
        private Long id;
    }

    @Test
    @DisplayName("应该正确存储 toSave 和 toDelete")
    void shouldStoreToSaveAndToDelete() {
        // Given
        OrderPO orderPO = new OrderPO();
        OrderItemPO itemPO = new OrderItemPO();
        DeletionInfo deletionInfo = new DeletionInfo(CustomerPO.class, 123L);

        List<Object> toSave = List.of(orderPO, itemPO);
        List<DeletionInfo> toDelete = List.of(deletionInfo);

        // When
        ReconstructedPos pos = new ReconstructedPos(toSave, toDelete);

        // Then
        assertThat(pos.toSave()).hasSize(2);
        assertThat(pos.toDelete()).hasSize(1);
    }

    @Test
    @DisplayName("getToSave 应该按类型过滤 PO")
    void shouldFilterToSaveByType() {
        // Given
        OrderPO orderPO = new OrderPO();
        OrderItemPO itemPO1 = new OrderItemPO();
        OrderItemPO itemPO2 = new OrderItemPO();

        List<Object> toSave = List.of(orderPO, itemPO1, itemPO2);
        ReconstructedPos pos = new ReconstructedPos(toSave, List.of());

        // When
        List<OrderPO> orderPOs = pos.getToSave(OrderPO.class);
        List<OrderItemPO> itemPOs = pos.getToSave(OrderItemPO.class);

        // Then
        assertThat(orderPOs).hasSize(1).containsExactly(orderPO);
        assertThat(itemPOs).hasSize(2).containsExactly(itemPO1, itemPO2);
    }

    @Test
    @DisplayName("getToDeleteIds 应该按类型过滤 ID")
    void shouldFilterToDeleteIdsByType() {
        // Given
        DeletionInfo info1 = new DeletionInfo(OrderItemPO.class, 100L);
        DeletionInfo info2 = new DeletionInfo(OrderItemPO.class, 200L);
        DeletionInfo info3 = new DeletionInfo(CustomerPO.class, 300L);

        List<DeletionInfo> toDelete = List.of(info1, info2, info3);
        ReconstructedPos pos = new ReconstructedPos(List.of(), toDelete);

        // When
        List<Long> itemIds = pos.getToDeleteIds(OrderItemPO.class);
        List<Long> customerIds = pos.getToDeleteIds(CustomerPO.class);

        // Then
        assertThat(itemIds).hasSize(2).containsExactly(100L, 200L);
        assertThat(customerIds).hasSize(1).containsExactly(300L);
    }

    @Test
    @DisplayName("空列表应该返回空结果")
    void shouldHandleEmptyLists() {
        // Given
        ReconstructedPos pos = new ReconstructedPos(List.of(), List.of());

        // When & Then
        assertThat(pos.getToSave(OrderPO.class)).isEmpty();
        assertThat(pos.getToDeleteIds(OrderItemPO.class)).isEmpty();
    }

    @Test
    @DisplayName("不存在的类型应该返回空列表")
    void shouldReturnEmptyListForNonExistentType() {
        // Given
        OrderPO orderPO = new OrderPO();
        DeletionInfo info = new DeletionInfo(OrderItemPO.class, 100L);
        ReconstructedPos pos = new ReconstructedPos(List.of(orderPO), List.of(info));

        // When & Then - 查询不存在的类型
        assertThat(pos.getToSave(CustomerPO.class)).isEmpty();
        assertThat(pos.getToDeleteIds(CustomerPO.class)).isEmpty();
    }

    @Test
    @DisplayName("应该正确处理多种 PO 类型混合")
    void shouldHandleMultiplePoTypes() {
        // Given
        OrderPO orderPO = new OrderPO();
        OrderItemPO itemPO1 = new OrderItemPO();
        OrderItemPO itemPO2 = new OrderItemPO();
        CustomerPO customerPO = new CustomerPO();

        DeletionInfo deleteItem = new DeletionInfo(OrderItemPO.class, 100L);
        DeletionInfo deleteCustomer = new DeletionInfo(CustomerPO.class, 200L);

        List<Object> toSave = List.of(orderPO, itemPO1, itemPO2, customerPO);
        List<DeletionInfo> toDelete = List.of(deleteItem, deleteCustomer);
        ReconstructedPos pos = new ReconstructedPos(toSave, toDelete);

        // When & Then
        assertThat(pos.getToSave(OrderPO.class)).hasSize(1).containsExactly(orderPO);
        assertThat(pos.getToSave(OrderItemPO.class)).hasSize(2).containsExactly(itemPO1, itemPO2);
        assertThat(pos.getToSave(CustomerPO.class)).hasSize(1).containsExactly(customerPO);
        assertThat(pos.getToDeleteIds(OrderItemPO.class)).hasSize(1).containsExactly(100L);
        assertThat(pos.getToDeleteIds(CustomerPO.class)).hasSize(1).containsExactly(200L);
    }
}
