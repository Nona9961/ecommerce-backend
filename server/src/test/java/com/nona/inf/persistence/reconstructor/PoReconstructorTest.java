package com.nona.inf.persistence.reconstructor;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.inf.persistence.converters.CompositePoConverter;
import com.nona.inf.persistence.converters.ConverterRegistry;
import com.nona.inf.persistence.converters.PoConverter;
import com.nona.inf.persistence.dispatcher.ChangeDispatcher;
import com.nona.inf.persistence.dispatcher.DispatchedChanges;
import com.nona.inf.persistence.po.BasePO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.nona.annotation.ScaffoldGenerated;

/**
 * PoReconstructor 单元测试
 */
@DisplayName("PoReconstructor 测试")
@ScaffoldGenerated
class PoReconstructorTest {

    // ========== 测试用类 ==========

    static class Order {
        private Long id;
        private String status;
        private List<OrderItem> items;
        private List<Spec> specs;

        Order(Long id, String status, List<OrderItem> items) {
            this.id = id;
            this.status = status;
            this.items = items;
            this.specs = List.of();
        }

        Order(Long id, String status, List<OrderItem> items, List<Spec> specs) {
            this.id = id;
            this.status = status;
            this.items = items;
            this.specs = specs;
        }

        Long getId() { return id; }
        String getStatus() { return status; }
        List<OrderItem> getItems() { return items; }
        List<Spec> getSpecs() { return specs; }
    }

    static class OrderItem {
        private Long id;
        private String name;

        OrderItem(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        Long getId() { return id; }
        String getName() { return name; }
    }

    static class Spec {
        private String id;  // 使用 key 作为 id
        private String value;

        Spec(String id, String value) {
            this.id = id;
            this.value = value;
        }

        String getId() { return id; }
        String getValue() { return value; }
    }

    static class OrderPO extends BasePO {
        private String status;

        String getStatus() { return status; }
        void setStatus(String status) { this.status = status; }
    }

    static class OrderItemPO extends BasePO {
        private String name;

        String getName() { return name; }
        void setName(String name) { this.name = name; }
    }

    static class SpecPO extends BasePO {
        private String key;
        private String value;

        String getKey() { return key; }
        void setKey(String key) { this.key = key; }
        String getValue() { return value; }
        void setValue(String value) { this.value = value; }
    }

    // ========== 测试依赖 ==========

    private ConverterRegistry converterRegistry;
    private ChangeDispatcher changeDispatcher;
    private PoReconstructor poReconstructor;

    @BeforeEach
    void setUp() {
        converterRegistry = mock(ConverterRegistry.class);
        changeDispatcher = mock(ChangeDispatcher.class);
        poReconstructor = new PoReconstructor(converterRegistry, changeDispatcher);
    }

    // ========== 测试用例 ==========

    @Test
    @DisplayName("主表变更时应该返回主表 PO")
    @SuppressWarnings("unchecked")
    void shouldReturnMainPoWhenMainTableChanged() {
        // Given
        final Order order = new Order(1L, "PAID", List.of());
        final OrderPO orderPO = new OrderPO();
        orderPO.setId(1L);
        orderPO.setStatus("PAID");

        final ChangeSet changeSet = new ChangeSet(List.of());
        final DispatchedChanges dispatched = new DispatchedChanges();
        dispatched.addMainTableChange(new ValueChange("status", "status", "status", null, false, "PENDING", "PAID"));

        final CompositePoConverter<Order, OrderPO> converter = mock(CompositePoConverter.class);
        when(converter.toMainPO(order)).thenReturn(orderPO);
        when(converterRegistry.getCompositeConverter(Order.class))
                .thenReturn((java.util.Optional) java.util.Optional.of(converter));
        when(changeDispatcher.dispatch(changeSet, Order.class)).thenReturn(dispatched);

        // When
        final ReconstructedPos result = poReconstructor.reconstruct(order, changeSet);

        // Then
        assertThat(result.getToSave(OrderPO.class)).hasSize(1);
        assertThat(result.getToSave(OrderPO.class).get(0).getStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("无变更时应该返回空结果")
    void shouldReturnEmptyWhenNoChanges() {
        // Given
        final Order order = new Order(1L, "PENDING", List.of());
        final ChangeSet changeSet = new ChangeSet(List.of());
        final DispatchedChanges dispatched = new DispatchedChanges();

        when(changeDispatcher.dispatch(changeSet, Order.class)).thenReturn(dispatched);

        // When
        final ReconstructedPos result = poReconstructor.reconstruct(order, changeSet);

        // Then
        assertThat(result.toSave()).isEmpty();
        assertThat(result.toDelete()).isEmpty();
    }

    @Test
    @DisplayName("子表新增时应该返回新增的子 PO")
    void shouldReturnAddedChildPo() {
        // Given
        final OrderItem item = new OrderItem(100L, "iPhone");
        final Order order = new Order(1L, "PENDING", List.of(item));

        final OrderItemPO itemPO = new OrderItemPO();
        itemPO.setId(100L);
        itemPO.setName("iPhone");

        final ChangeSet changeSet = new ChangeSet(List.of());
        final DispatchedChanges dispatched = new DispatchedChanges();

        final ObjectNode addedNode = new ObjectNode(java.util.Map.of(), 100L);
        dispatched.getOrCreateCollectionChanges("items").addAddition(new ItemAddedChange("[100]", "items[100]", null, "items", true, addedNode));

        final PoConverter<OrderItem, OrderItemPO> itemConverter = mock(PoConverter.class);
        when(itemConverter.toPO(item)).thenReturn(itemPO);
        when(itemConverter.poClass()).thenReturn(OrderItemPO.class);
        when(converterRegistry.getAllConverters()).thenReturn(Map.of("items", itemConverter));
        when(changeDispatcher.dispatch(changeSet, Order.class)).thenReturn(dispatched);

        // When
        final ReconstructedPos result = poReconstructor.reconstruct(order, changeSet);

        // Then
        assertThat(result.getToSave(OrderItemPO.class)).hasSize(1);
        assertThat(result.getToSave(OrderItemPO.class).get(0).getName()).isEqualTo("iPhone");
    }

    @Test
    @DisplayName("子表删除时应该返回删除的 ID")
    void shouldReturnDeletedIds() {
        // Given
        final Order order = new Order(1L, "PENDING", List.of());
        final ChangeSet changeSet = new ChangeSet(List.of());
        final DispatchedChanges dispatched = new DispatchedChanges();

        final ObjectNode removedNode = new ObjectNode(java.util.Map.of(), 200L);
        dispatched.getOrCreateCollectionChanges("items").addRemoval(new ItemRemovedChange("[200]", "items[200]", null, "items", true, removedNode));

        final PoConverter<OrderItem, OrderItemPO> itemConverter = mock(PoConverter.class);
        when(itemConverter.poClass()).thenReturn(OrderItemPO.class);
        when(converterRegistry.getAllConverters()).thenReturn(Map.of("items", itemConverter));
        when(changeDispatcher.dispatch(changeSet, Order.class)).thenReturn(dispatched);

        // When
        final ReconstructedPos result = poReconstructor.reconstruct(order, changeSet);

        // Then
        assertThat(result.getToDeleteIds(OrderItemPO.class)).hasSize(1);
        assertThat(result.getToDeleteIds(OrderItemPO.class).get(0)).isEqualTo(200L);
    }

    @Test
    @DisplayName("子表字段变更时应该返回更新的子 PO")
    void shouldReturnUpdatedChildPo() {
        // Given
        final OrderItem item = new OrderItem(100L, "iPhone Pro");
        final Order order = new Order(1L, "PENDING", List.of(item));

        final OrderItemPO itemPO = new OrderItemPO();
        itemPO.setId(100L);
        itemPO.setName("iPhone Pro");

        final ChangeSet changeSet = new ChangeSet(List.of());
        final DispatchedChanges dispatched = new DispatchedChanges();
        dispatched.getOrCreateCollectionChanges("items").addFieldChange(new ValueChange("name", "items[100].name", "name", "items", true, "iPhone", "iPhone Pro"));

        final PoConverter<OrderItem, OrderItemPO> itemConverter = mock(PoConverter.class);
        when(itemConverter.toPO(item)).thenReturn(itemPO);
        when(itemConverter.poClass()).thenReturn(OrderItemPO.class);
        when(converterRegistry.getAllConverters()).thenReturn(Map.of("items", itemConverter));
        when(changeDispatcher.dispatch(changeSet, Order.class)).thenReturn(dispatched);

        // When
        final ReconstructedPos result = poReconstructor.reconstruct(order, changeSet);

        // Then
        assertThat(result.getToSave(OrderItemPO.class)).hasSize(1);
        assertThat(result.getToSave(OrderItemPO.class).get(0).getName()).isEqualTo("iPhone Pro");
    }

    @Test
    @DisplayName("混合变更时应该同时返回主表和子表 PO")
    @SuppressWarnings("unchecked")
    void shouldReturnBothMainAndChildPoWhenMixedChanges() {
        // Given
        final OrderItem item = new OrderItem(100L, "iPhone");
        final Order order = new Order(1L, "PAID", List.of(item));

        final OrderPO orderPO = new OrderPO();
        orderPO.setId(1L);
        orderPO.setStatus("PAID");

        final OrderItemPO itemPO = new OrderItemPO();
        itemPO.setId(100L);
        itemPO.setName("iPhone");

        final ChangeSet changeSet = new ChangeSet(List.of());
        final DispatchedChanges dispatched = new DispatchedChanges();
        // 主表变更
        dispatched.addMainTableChange(new ValueChange("status", "status", "status", null, false, "PENDING", "PAID"));
        // 子表新增
        final ObjectNode addedNode = new ObjectNode(java.util.Map.of(), 100L);
        dispatched.getOrCreateCollectionChanges("items").addAddition(new ItemAddedChange("[100]", "items[100]", null, "items", true, addedNode));

        final CompositePoConverter<Order, OrderPO> mainConverter = mock(CompositePoConverter.class);
        when(mainConverter.toMainPO(order)).thenReturn(orderPO);
        when(converterRegistry.getCompositeConverter(Order.class))
                .thenReturn((java.util.Optional) java.util.Optional.of(mainConverter));

        final PoConverter<OrderItem, OrderItemPO> itemConverter = mock(PoConverter.class);
        when(itemConverter.toPO(item)).thenReturn(itemPO);
        when(itemConverter.poClass()).thenReturn(OrderItemPO.class);
        when(converterRegistry.getAllConverters()).thenReturn(Map.of("items", itemConverter));
        when(changeDispatcher.dispatch(changeSet, Order.class)).thenReturn(dispatched);

        // When
        final ReconstructedPos result = poReconstructor.reconstruct(order, changeSet);

        // Then
        assertThat(result.getToSave(OrderPO.class)).hasSize(1);
        assertThat(result.getToSave(OrderPO.class).get(0).getStatus()).isEqualTo("PAID");
        assertThat(result.getToSave(OrderItemPO.class)).hasSize(1);
        assertThat(result.getToSave(OrderItemPO.class).get(0).getName()).isEqualTo("iPhone");
    }

    @Test
    @DisplayName("多个子对象同时变更时应该返回所有变更的 PO")
    void shouldReturnAllChangedChildPosWhenMultipleChanges() {
        // Given
        final OrderItem item1 = new OrderItem(100L, "iPhone");
        final OrderItem item2 = new OrderItem(200L, "iPad");
        final Order order = new Order(1L, "PENDING", List.of(item1, item2));

        final OrderItemPO itemPO1 = new OrderItemPO();
        itemPO1.setId(100L);
        itemPO1.setName("iPhone");

        final OrderItemPO itemPO2 = new OrderItemPO();
        itemPO2.setId(200L);
        itemPO2.setName("iPad");

        final ChangeSet changeSet = new ChangeSet(List.of());
        final DispatchedChanges dispatched = new DispatchedChanges();

        // 新增 item1
        final ObjectNode addedNode1 = new ObjectNode(java.util.Map.of(), 100L);
        dispatched.getOrCreateCollectionChanges("items").addAddition(new ItemAddedChange("[100]", "items[100]", null, "items", true, addedNode1));

        // 删除 item (id=300)
        final ObjectNode removedNode = new ObjectNode(java.util.Map.of(), 300L);
        dispatched.getOrCreateCollectionChanges("items").addRemoval(new ItemRemovedChange("[200]", "items[200]", null, "items", true, removedNode));

        // 更新 item2
        dispatched.getOrCreateCollectionChanges("items").addFieldChange(new ValueChange("name", "items[200].name", "name", "items", true, "iPad Mini", "iPad"));

        final PoConverter<OrderItem, OrderItemPO> itemConverter = mock(PoConverter.class);
        when(itemConverter.toPO(item1)).thenReturn(itemPO1);
        when(itemConverter.toPO(item2)).thenReturn(itemPO2);
        when(itemConverter.poClass()).thenReturn(OrderItemPO.class);
        when(converterRegistry.getAllConverters()).thenReturn(Map.of("items", itemConverter));
        when(changeDispatcher.dispatch(changeSet, Order.class)).thenReturn(dispatched);

        // When
        final ReconstructedPos result = poReconstructor.reconstruct(order, changeSet);

        // Then
        // 新增 + 更新 = 2 个 toSave
        assertThat(result.getToSave(OrderItemPO.class)).hasSize(2);
        // 删除 = 1 个 toDelete
        assertThat(result.getToDeleteIds(OrderItemPO.class)).hasSize(1);
        assertThat(result.getToDeleteIds(OrderItemPO.class).get(0)).isEqualTo(300L);
    }

    @Test
    @DisplayName("没有注册 CompositeConverter 时主表变更应该被忽略")
    void shouldIgnoreMainTableChangesWhenNoCompositeConverter() {
        // Given
        final Order order = new Order(1L, "PAID", List.of());
        final ChangeSet changeSet = new ChangeSet(List.of());
        final DispatchedChanges dispatched = new DispatchedChanges();
        dispatched.addMainTableChange(new ValueChange("status", "status", "status", null, false, "PENDING", "PAID"));

        // 没有注册 CompositeConverter
        when(converterRegistry.getCompositeConverter(Order.class)).thenReturn(java.util.Optional.empty());
        when(converterRegistry.getAllConverters()).thenReturn(Map.of());
        when(changeDispatcher.dispatch(changeSet, Order.class)).thenReturn(dispatched);

        // When
        final ReconstructedPos result = poReconstructor.reconstruct(order, changeSet);

        // Then - 主表变更被忽略
        assertThat(result.toSave()).isEmpty();
        assertThat(result.toDelete()).isEmpty();
    }

    @Test
    @DisplayName("子表 converter 不存在时应该跳过该子表变更")
    void shouldSkipChildChangesWhenConverterNotFound() {
        // Given
        final OrderItem item = new OrderItem(100L, "iPhone");
        final Order order = new Order(1L, "PENDING", List.of(item));
        final ChangeSet changeSet = new ChangeSet(List.of());
        final DispatchedChanges dispatched = new DispatchedChanges();

        final ObjectNode addedNode = new ObjectNode(java.util.Map.of(), 100L);
        dispatched.getOrCreateCollectionChanges("items").addAddition(new ItemAddedChange("[100]", "items[100]", null, "items", true, addedNode));

        // 没有注册 items 的 converter
        when(converterRegistry.getAllConverters()).thenReturn(Map.of());
        when(changeDispatcher.dispatch(changeSet, Order.class)).thenReturn(dispatched);

        // When
        final ReconstructedPos result = poReconstructor.reconstruct(order, changeSet);

        // Then - 子表变更被跳过
        assertThat(result.toSave()).isEmpty();
        assertThat(result.toDelete()).isEmpty();
    }

    @Test
    @DisplayName("找不到子对象时应该跳过该对象")
    void shouldSkipWhenChildObjectNotFound() {
        // Given
        final Order order = new Order(1L, "PENDING", List.of()); // 空的 items 列表
        final ChangeSet changeSet = new ChangeSet(List.of());
        final DispatchedChanges dispatched = new DispatchedChanges();

        // 新增一个不存在于 root 中的 item
        final ObjectNode addedNode = new ObjectNode(java.util.Map.of(), 999L); // 这个 ID 在 order.items 中不存在
        dispatched.getOrCreateCollectionChanges("items").addAddition(new ItemAddedChange("[100]", "items[100]", null, "items", true, addedNode));

        final PoConverter<OrderItem, OrderItemPO> itemConverter = mock(PoConverter.class);
        when(itemConverter.poClass()).thenReturn(OrderItemPO.class);
        when(converterRegistry.getAllConverters()).thenReturn(Map.of("items", itemConverter));
        when(changeDispatcher.dispatch(changeSet, Order.class)).thenReturn(dispatched);

        // When
        final ReconstructedPos result = poReconstructor.reconstruct(order, changeSet);

        // Then - 找不到的对象被跳过，不添加到 toSave
        assertThat(result.toSave()).isEmpty();
        verify(itemConverter, never()).toPO(any());
    }

    @Test
    @DisplayName("String 类型的 identifier 应该正确解析")
    void shouldParseStringIdentifier() {
        // Given
        final Spec spec = new Spec("color", "red");
        final Order order = new Order(1L, "PENDING", List.of(), List.of(spec));

        final SpecPO specPO = new SpecPO();
        specPO.setKey("color");
        specPO.setValue("red");

        final ChangeSet changeSet = new ChangeSet(List.of());
        final DispatchedChanges dispatched = new DispatchedChanges();
        // String identifier: specs[color].value
        dispatched.getOrCreateCollectionChanges("specs").addFieldChange(new ValueChange("value", "specs[color].value", "value", "specs", true, "blue", "red"));

        final PoConverter<Spec, SpecPO> specConverter = mock(PoConverter.class);
        when(specConverter.toPO(spec)).thenReturn(specPO);
        when(specConverter.poClass()).thenReturn(SpecPO.class);
        when(converterRegistry.getAllConverters()).thenReturn(Map.of("specs", specConverter));
        when(changeDispatcher.dispatch(changeSet, Order.class)).thenReturn(dispatched);

        // When
        final ReconstructedPos result = poReconstructor.reconstruct(order, changeSet);

        // Then - String identifier "color" 应该被正确解析
        assertThat(result.getToSave(SpecPO.class)).hasSize(1);
    }
}
