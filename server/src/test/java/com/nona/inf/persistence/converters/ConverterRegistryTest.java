package com.nona.inf.persistence.converters;

import com.nona.inf.persistence.po.BasePO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import com.nona.annotation.ScaffoldGenerated;

/**
 * ConverterRegistry 单元测试
 */
@ScaffoldGenerated
class ConverterRegistryTest {

    private ConverterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ConverterRegistry();
    }

    // ========== 测试用领域对象和 PO ==========

    static class OrderItem {
        Long id;
        String name;
    }

    static class OrderItemPO {
        Long id;
        String name;
    }

    static class ShippingInfo {
        String address;
    }

    static class ShippingInfoPO {
        String address;
    }

    static class Order {
        Long id;
        String status;
        List<OrderItem> items;
        ShippingInfo shippingInfo;
        List<String> tags;  // 值对象集合，不应被识别为子表
    }

    static class OrderPO extends BasePO {
        String status;
    }

    // ========== 测试用转换器 ==========

    static class OrderItemConverter implements PoConverter<OrderItem, OrderItemPO> {
        @Override
        public Class<OrderItem> domainClass() { return OrderItem.class; }
        @Override
        public Class<OrderItemPO> poClass() { return OrderItemPO.class; }
        @Override
        public OrderItemPO toPO(OrderItem domain) { return new OrderItemPO(); }
        @Override
        public OrderItem toDomain(OrderItemPO po) { return new OrderItem(); }
    }

    static class ShippingInfoConverter implements PoConverter<ShippingInfo, ShippingInfoPO> {
        @Override
        public Class<ShippingInfo> domainClass() { return ShippingInfo.class; }
        @Override
        public Class<ShippingInfoPO> poClass() { return ShippingInfoPO.class; }
        @Override
        public ShippingInfoPO toPO(ShippingInfo domain) { return new ShippingInfoPO(); }
        @Override
        public ShippingInfo toDomain(ShippingInfoPO po) { return new ShippingInfo(); }
    }

    static class OrderConverter implements CompositePoConverter<Order, OrderPO> {
        @Override
        public Class<Order> rootClass() { return Order.class; }
        @Override
        public Class<OrderPO> mainPoClass() { return OrderPO.class; }
        @Override
        public OrderPO toMainPO(Order root) { return new OrderPO(); }
        @Override
        public Order toRoot(OrderPO mainPO, Map<String, Object> childData) { return new Order(); }
    }

    // ========== 简单转换器注册测试 ==========

    @Test
    void shouldRegisterAndRetrieveSimpleConverter() {
        OrderItemConverter converter = new OrderItemConverter();
        registry.register(converter);

        Optional<PoConverter<OrderItem, OrderItemPO>> result = registry.getConverter(OrderItem.class);

        assertTrue(result.isPresent());
        assertSame(converter, result.get());
    }

    @Test
    void shouldReturnEmptyWhenConverterNotFound() {
        Optional<PoConverter<OrderItem, OrderItemPO>> result = registry.getConverter(OrderItem.class);

        assertTrue(result.isEmpty());
    }

    // ========== 组合转换器注册测试 ==========

    @Test
    void shouldRegisterAndRetrieveCompositeConverter() {
        OrderConverter converter = new OrderConverter();
        registry.register(converter);

        Optional<CompositePoConverter<Order, OrderPO>> result = registry.getCompositeConverter(Order.class);

        assertTrue(result.isPresent());
        assertSame(converter, result.get());
    }

    // ========== 子表自动扫描测试 ==========

    @Test
    void shouldAutoScanCollectionFieldAsChildTable() {
        // 注册子表转换器
        registry.register(new OrderItemConverter());
        registry.register(new OrderConverter());

        // 自动扫描应该发现 items 字段
        Map<String, PoConverter<?, ?>> childConverters = registry.getChildConverters(Order.class);

        assertTrue(childConverters.containsKey("items"));
        assertEquals(OrderItem.class, childConverters.get("items").domainClass());
    }

    @Test
    void shouldAutoScanSingleObjectFieldAsChildTable() {
        // 注册子表转换器
        registry.register(new ShippingInfoConverter());
        registry.register(new OrderConverter());

        // 自动扫描应该发现 shippingInfo 字段
        Map<String, PoConverter<?, ?>> childConverters = registry.getChildConverters(Order.class);

        assertTrue(childConverters.containsKey("shippingInfo"));
        assertEquals(ShippingInfo.class, childConverters.get("shippingInfo").domainClass());
    }

    @Test
    void shouldNotScanUnregisteredTypeAsChildTable() {
        // 只注册 OrderConverter，不注册 OrderItemConverter
        registry.register(new OrderConverter());

        Map<String, PoConverter<?, ?>> childConverters = registry.getChildConverters(Order.class);

        // tags 是 List<String>，String 未注册，不应被识别
        assertFalse(childConverters.containsKey("tags"));
        // items 的 OrderItem 未注册，也不应被识别
        assertFalse(childConverters.containsKey("items"));
    }

    // ========== 手动声明覆盖测试 ==========

    @Test
    void shouldMergeManualDeclarationWithAutoScan() {
        OrderItemConverter itemConverter = new OrderItemConverter();
        registry.register(itemConverter);

        // 带手动声明的 OrderConverter
        CompositePoConverter<Order, OrderPO> converterWithDeclaration = new CompositePoConverter<>() {
            @Override
            public Class<Order> rootClass() { return Order.class; }
            @Override
            public Class<OrderPO> mainPoClass() { return OrderPO.class; }
            @Override
            public OrderPO toMainPO(Order root) { return new OrderPO(); }
            @Override
            public Order toRoot(OrderPO mainPO, Map<String, Object> childData) { return new Order(); }
            @Override
            public Map<String, PoConverter<?, ?>> declareChildConverters() {
                // 手动声明一个新的映射
                return Map.of("customItems", itemConverter);
            }
        };
        registry.register(converterWithDeclaration);

        Map<String, PoConverter<?, ?>> childConverters = registry.getChildConverters(Order.class);

        // 自动扫描的 items
        assertTrue(childConverters.containsKey("items"));
        // 手动声明的 customItems
        assertTrue(childConverters.containsKey("customItems"));
    }

    @Test
    void shouldManualDeclarationOverrideAutoScan() {
        OrderItemConverter autoConverter = new OrderItemConverter();
        OrderItemConverter manualConverter = new OrderItemConverter();  // 不同实例
        registry.register(autoConverter);

        CompositePoConverter<Order, OrderPO> converterWithOverride = new CompositePoConverter<>() {
            @Override
            public Class<Order> rootClass() { return Order.class; }
            @Override
            public Class<OrderPO> mainPoClass() { return OrderPO.class; }
            @Override
            public OrderPO toMainPO(Order root) { return new OrderPO(); }
            @Override
            public Order toRoot(OrderPO mainPO, Map<String, Object> childData) { return new Order(); }
            @Override
            public Map<String, PoConverter<?, ?>> declareChildConverters() {
                // 手动覆盖 items 映射
                return Map.of("items", manualConverter);
            }
        };
        registry.register(converterWithOverride);

        Map<String, PoConverter<?, ?>> childConverters = registry.getChildConverters(Order.class);

        // 应该使用手动声明的转换器
        assertSame(manualConverter, childConverters.get("items"));
    }
}
