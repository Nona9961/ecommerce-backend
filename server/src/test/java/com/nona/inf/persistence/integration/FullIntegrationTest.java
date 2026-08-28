package com.nona.inf.persistence.integration;

import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.changeTracking.internal.capability.DefaultTrackingCapabilityProvider;
import com.nona.inf.persistence.converters.CompositePoConverter;
import com.nona.inf.persistence.converters.ConverterRegistry;
import com.nona.inf.persistence.converters.PoConverter;
import com.nona.inf.persistence.po.TenantScopedBasePO;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import com.nona.annotation.ScaffoldGenerated;

/**
 * ==================== 复杂领域模型（4层嵌套） ====================
 *
 * Order (聚合根) - 第1层
 * ├── id, orderNo, status
 * ├── totalAmount: Money (值对象)
 * ├── customer: Customer (单实体，一对一) - 第2层
 * │   ├── id, name
 * │   ├── contact: ContactInfo (值对象)
 * │   └── addresses: List<Address> - 第3层
 * ├── items: List<OrderItem> - 第2层
 * │   ├── id, sku, productName, quantity
 * │   ├── unitPrice: Money (值对象)
 * │   └── subItems: List<SubItem> - 第3层
 * │       └── specs: List<Spec> - 第4层
 * ├── tags: List<String> (简单类型集合)
 * └── extensions: Map<String, String>
 */

/**
 * DDD 持久化方案完整集成测试
 * <p>
 * <b>测试范围</b>：
 * <ul>
 *     <li>✅ <b>DifferRepository</b> 的完整生命周期（getByID, save, delete）</li>
 *     <li>✅ <b>ChangeApplier</b> 自动生成 SQL（UPDATE/INSERT/DELETE）</li>
 *     <li>✅ <b>ChangeTracker + ChangeSet</b> 变更追踪</li>
 *     <li>✅ <b>端到端持久化流程</b>（repository.getByID() → 修改 → repository.save() → 验证）</li>
 *     <li>✅ 复杂对象图（4层嵌套 + Map + 单实体 + 值对象）</li>
 * </ul>
 *
 * <p>
 * <b>核心验证流程</b>：
 * <pre>
 * 1. repository.getByID(1L)  → 加载聚合根 + 自动注册快照
 * 2. order.setStatus("PAID") → 修改聚合根
 * 3. repository.save(order)  → 自动计算 ChangeSet + ChangeApplier 生成 SQL
 * 4. 重新加载并验证数据库状态
 * </pre>
 *
 * @see com.nona.inf.persistence.repository.DifferRepository
 * @see OrderRepository
 */
@ScaffoldGenerated
class FullIntegrationTest {

    private JdbcTemplate jdbc;
    private ConverterRegistry registry;
    private DefaultTrackingCapabilityProvider trackingProvider;
    private OrderConverter orderConverter;
    private OrderItemConverter itemConverter;
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        // H2 内存数据库
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build();
        jdbc = new JdbcTemplate(dataSource);

        // 建表
        jdbc.execute("DROP TABLE IF EXISTS t_spec");
        jdbc.execute("DROP TABLE IF EXISTS t_sub_item");
        jdbc.execute("DROP TABLE IF EXISTS t_order_item");
        jdbc.execute("DROP TABLE IF EXISTS t_address");
        jdbc.execute("DROP TABLE IF EXISTS t_customer");
        jdbc.execute("DROP TABLE IF EXISTS t_order");
        jdbc.execute("""
            CREATE TABLE t_order (
                id BIGINT PRIMARY KEY,
                order_no VARCHAR(64),
                status VARCHAR(32),
                total_amount DECIMAL(18,2),
                total_currency VARCHAR(8),
                tenant_id VARCHAR(64),
                create_time TIMESTAMP,
                update_time TIMESTAMP
            )
        """);
        jdbc.execute("""
            CREATE TABLE t_customer (
                id BIGINT PRIMARY KEY,
                order_id BIGINT,
                name VARCHAR(64),
                contact_phone VARCHAR(32),
                contact_email VARCHAR(64)
            )
        """);
        jdbc.execute("""
            CREATE TABLE t_address (
                id BIGINT PRIMARY KEY,
                customer_id BIGINT,
                type VARCHAR(16),
                city VARCHAR(64)
            )
        """);
        jdbc.execute("""
            CREATE TABLE t_order_item (
                id BIGINT PRIMARY KEY,
                order_id BIGINT,
                sku VARCHAR(64),
                product_name VARCHAR(128),
                quantity INT,
                unit_price DECIMAL(18,2),
                unit_currency VARCHAR(8)
            )
        """);
        jdbc.execute("""
            CREATE TABLE t_sub_item (
                id BIGINT PRIMARY KEY,
                order_item_id BIGINT,
                name VARCHAR(64)
            )
        """);
        jdbc.execute("""
            CREATE TABLE t_spec (
                id BIGINT PRIMARY KEY,
                sub_item_id BIGINT,
                spec_key VARCHAR(64),
                spec_value VARCHAR(128)
            )
        """);

        // 转换器
        orderConverter = new OrderConverter();
        itemConverter = new OrderItemConverter();
        CustomerConverter customerConverter = new CustomerConverter();

        // 注册转换器
        registry = new ConverterRegistry();
        registry.register(itemConverter);
        registry.register(customerConverter);  // 注册 customer 转换器
        registry.register(orderConverter);

        // Change Tracking 配置
        trackingProvider = new DefaultTrackingCapabilityProvider();
        trackingProvider.withIdentifier(OrderItem.class, OrderItem::getId);
        trackingProvider.withIdentifier(SubItem.class, SubItem::getId);
        trackingProvider.withIdentifier(Spec.class, Spec::getKey);
        trackingProvider.withIdentifier(Customer.class, Customer::getId);
        trackingProvider.withIdentifier(Address.class, Address::getId);
        // 值对象配置
        trackingProvider.withValueType(Money.class);
        trackingProvider.withValueType(ContactInfo.class);

        // 初始化 OrderRepository
        com.nona.inf.context.ThreadContext threadContext = new com.nona.inf.context.ThreadContext();
        com.nona.inf.persistence.tracking.ChangeTrackerProvider changeTrackerProvider =
                com.nona.inf.persistence.tracking.ChangeTrackerProvider.builder()
                        .withIdentifier(OrderItem.class, OrderItem::getId)
                        .withIdentifier(SubItem.class, SubItem::getId)
                        .withIdentifier(Spec.class, Spec::getKey)
                        .withIdentifier(Customer.class, Customer::getId)
                        .withIdentifier(Address.class, Address::getId)
                        .withValueType(Money.class)
                        .withValueType(ContactInfo.class)
                        .build();

        // 创建 Spring Data Repository 的模拟实现
        org.springframework.data.repository.ListCrudRepository<OrderPO, Long> crudRepo =
                new InMemoryOrderPORepository(jdbc, orderConverter);

        orderRepository = new OrderRepository(crudRepo, threadContext, orderConverter,
                changeTrackerProvider, jdbc, registry);
    }

    // ==================== 值对象 ====================

    /** 金额值对象 */
    record Money(BigDecimal amount, String currency) {
        static Money of(BigDecimal amount) { return new Money(amount, "CNY"); }
    }

    /** 联系信息值对象 */
    record ContactInfo(String phone, String email) {}

    // ==================== 领域模型 (DO) - 4层嵌套 ====================

    /** 第4层：规格 */
    static class Spec {
        private String key;
        private String value;
        Spec() {}
        Spec(String key, String value) { this.key = key; this.value = value; }
        String getKey() { return key; }
        String getValue() { return value; }
        void setValue(String value) { this.value = value; }
    }

    /** 第3层：子项 */
    static class SubItem {
        private Long id;
        private String name;
        private List<Spec> specs = new ArrayList<>();
        SubItem() {}
        SubItem(Long id, String name) { this.id = id; this.name = name; }
        Long getId() { return id; }
        String getName() { return name; }
        void setName(String name) { this.name = name; }
        List<Spec> getSpecs() { return specs; }
    }

    /** 第3层：地址 */
    static class Address {
        private Long id;
        private String type;
        private String city;
        Address() {}
        Address(Long id, String type, String city) { this.id = id; this.type = type; this.city = city; }
        Long getId() { return id; }
        String getType() { return type; }
        String getCity() { return city; }
        void setCity(String city) { this.city = city; }
    }

    /** 第2层：客户（一对一） */
    static class Customer {
        private Long id;
        private String name;
        private ContactInfo contact;
        private List<Address> addresses = new ArrayList<>();
        Customer() {}
        Customer(Long id, String name) { this.id = id; this.name = name; }
        Long getId() { return id; }
        String getName() { return name; }
        void setName(String name) { this.name = name; }
        ContactInfo getContact() { return contact; }
        void setContact(ContactInfo contact) { this.contact = contact; }
        List<Address> getAddresses() { return addresses; }
    }

    /** 第2层：订单项 */
    static class OrderItem {
        private Long id;
        private String sku;
        private String productName;
        private int quantity;
        private Money unitPrice;
        private List<SubItem> subItems = new ArrayList<>();
        private Map<String, String> attributes = new HashMap<>();

        OrderItem() {}
        OrderItem(Long id, String sku, String productName, int quantity, Money unitPrice) {
            this.id = id; this.sku = sku; this.productName = productName;
            this.quantity = quantity; this.unitPrice = unitPrice;
        }

        Long getId() { return id; }
        String getSku() { return sku; }
        String getProductName() { return productName; }
        void setProductName(String productName) { this.productName = productName; }
        int getQuantity() { return quantity; }
        void setQuantity(int quantity) { this.quantity = quantity; }
        Money getUnitPrice() { return unitPrice; }
        void setUnitPrice(Money unitPrice) { this.unitPrice = unitPrice; }
        List<SubItem> getSubItems() { return subItems; }
        Map<String, String> getAttributes() { return attributes; }
    }

    /** 第1层：订单（聚合根） */
    static class Order {
        private Long id;
        private String orderNo;
        private String status;
        private Money totalAmount;
        private Customer customer;
        private List<OrderItem> items = new ArrayList<>();
        private List<String> tags = new ArrayList<>();
        private Map<String, String> extensions = new HashMap<>();

        Order() {}
        Order(Long id, String orderNo) { this.id = id; this.orderNo = orderNo; this.status = "PENDING"; }

        Long getId() { return id; }
        void setId(Long id) { this.id = id; }
        String getOrderNo() { return orderNo; }
        String getStatus() { return status; }
        void setStatus(String status) { this.status = status; }
        Money getTotalAmount() { return totalAmount; }
        void setTotalAmount(Money totalAmount) { this.totalAmount = totalAmount; }
        Customer getCustomer() { return customer; }
        void setCustomer(Customer customer) { this.customer = customer; }
        List<OrderItem> getItems() { return items; }
        List<String> getTags() { return tags; }
        Map<String, String> getExtensions() { return extensions; }
    }

    // ==================== 持久化对象 (PO) ====================

    static class OrderPO extends TenantScopedBasePO {
        private String orderNo;
        private String status;
        private BigDecimal totalAmount;
        private String totalCurrency;

        String getOrderNo() { return orderNo; }
        void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        String getStatus() { return status; }
        void setStatus(String status) { this.status = status; }
        BigDecimal getTotalAmount() { return totalAmount; }
        void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
        String getTotalCurrency() { return totalCurrency; }
        void setTotalCurrency(String totalCurrency) { this.totalCurrency = totalCurrency; }
    }

    static class OrderItemPO {
        private Long id;
        private Long orderId;
        private String sku;
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;
        private String unitCurrency;

        Long getId() { return id; }
        void setId(Long id) { this.id = id; }
        Long getOrderId() { return orderId; }
        void setOrderId(Long orderId) { this.orderId = orderId; }
        String getSku() { return sku; }
        void setSku(String sku) { this.sku = sku; }
        String getProductName() { return productName; }
        void setProductName(String productName) { this.productName = productName; }
        int getQuantity() { return quantity; }
        void setQuantity(int quantity) { this.quantity = quantity; }
        BigDecimal getUnitPrice() { return unitPrice; }
        void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
        String getUnitCurrency() { return unitCurrency; }
        void setUnitCurrency(String unitCurrency) { this.unitCurrency = unitCurrency; }
    }

    // ==================== 转换器实现 ====================

    static class OrderItemConverter implements PoConverter<OrderItem, OrderItemPO> {
        @Override
        public Class<OrderItem> domainClass() { return OrderItem.class; }

        @Override
        public Class<OrderItemPO> poClass() { return OrderItemPO.class; }

        @Override
        public OrderItemPO toPO(OrderItem domain) {
            OrderItemPO po = new OrderItemPO();
            po.setId(domain.getId());
            po.setSku(domain.getSku());
            po.setProductName(domain.getProductName());
            po.setQuantity(domain.getQuantity());
            if (domain.getUnitPrice() != null) {
                po.setUnitPrice(domain.getUnitPrice().amount());
                po.setUnitCurrency(domain.getUnitPrice().currency());
            }
            return po;
        }

        @Override
        public OrderItem toDomain(OrderItemPO po) {
            Money unitPrice = po.getUnitPrice() != null
                ? new Money(po.getUnitPrice(), po.getUnitCurrency()) : null;
            return new OrderItem(po.getId(), po.getSku(), po.getProductName(),
                    po.getQuantity(), unitPrice);
        }
    }

    static class CustomerConverter implements PoConverter<Customer, Customer> {
        @Override
        public Class<Customer> domainClass() { return Customer.class; }

        @Override
        public Class<Customer> poClass() { return Customer.class; }

        @Override
        public Customer toPO(Customer domain) {
            // Customer 本身就是 PO（简化）
            return domain;
        }

        @Override
        public Customer toDomain(Customer po) {
            return po;
        }
    }

    static class OrderConverter implements CompositePoConverter<Order, OrderPO>,
            com.nona.inf.persistence.converters.RdbGeneralConvertor<Order, OrderPO, Map<String, Object>> {
        @Override
        public Class<Order> rootClass() { return Order.class; }

        @Override
        public Class<OrderPO> mainPoClass() { return OrderPO.class; }

        @Override
        public Class<OrderPO> poClass() { return OrderPO.class; }

        @Override
        public OrderPO convertToPO(Order root) {
            return toMainPO(root);
        }

        @Override
        public Order convertToRoot(OrderPO po, Map<String, Object> other) {
            return toRoot(po, other);
        }

        @Override
        public OrderPO toMainPO(Order root) {
            OrderPO po = new OrderPO();
            po.setId(root.getId());
            po.setOrderNo(root.getOrderNo());
            po.setStatus(root.getStatus());
            if (root.getTotalAmount() != null) {
                po.setTotalAmount(root.getTotalAmount().amount());
                po.setTotalCurrency(root.getTotalAmount().currency());
            }
            po.setTenantID("test-tenant");
            po.setCreateTime(LocalDateTime.now());
            po.setUpdateTime(LocalDateTime.now());
            return po;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Order toRoot(OrderPO mainPO, Map<String, Object> childData) {
            Order order = new Order();
            order.setId(mainPO.getId());
            order.setStatus(mainPO.getStatus());
            if (mainPO.getTotalAmount() != null) {
                order.setTotalAmount(new Money(mainPO.getTotalAmount(), mainPO.getTotalCurrency()));
            }

            if (childData != null) {
                List<OrderItem> items = (List<OrderItem>) childData.getOrDefault("items", Collections.emptyList());
                order.getItems().addAll(items);

                Customer customer = (Customer) childData.get("customer");
                order.setCustomer(customer);
            }
            return order;
        }
    }

    // ==================== CRUD 辅助方法 ====================

    private void insertOrder(OrderPO po) {
        jdbc.update("""
            INSERT INTO t_order (id, order_no, status, total_amount, total_currency, tenant_id, create_time, update_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, po.getId(), po.getOrderNo(), po.getStatus(), po.getTotalAmount(),
           po.getTotalCurrency(), po.getTenantID(), po.getCreateTime(), po.getUpdateTime());
    }

    private void insertCustomer(Customer c, Long orderId) {
        jdbc.update("""
            INSERT INTO t_customer (id, order_id, name, contact_phone, contact_email)
            VALUES (?, ?, ?, ?, ?)
        """, c.getId(), orderId, c.getName(),
           c.getContact() != null ? c.getContact().phone() : null,
           c.getContact() != null ? c.getContact().email() : null);
        for (Address addr : c.getAddresses()) {
            jdbc.update("INSERT INTO t_address (id, customer_id, type, city) VALUES (?, ?, ?, ?)",
                addr.getId(), c.getId(), addr.getType(), addr.getCity());
        }
    }

    private void insertOrderItem(OrderItem item, Long orderId) {
        jdbc.update("""
            INSERT INTO t_order_item (id, order_id, sku, product_name, quantity, unit_price, unit_currency)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, item.getId(), orderId, item.getSku(), item.getProductName(), item.getQuantity(),
           item.getUnitPrice() != null ? item.getUnitPrice().amount() : null,
           item.getUnitPrice() != null ? item.getUnitPrice().currency() : null);
        for (SubItem sub : item.getSubItems()) {
            insertSubItem(sub, item.getId());
        }
    }

    private void insertSubItem(SubItem sub, Long itemId) {
        jdbc.update("INSERT INTO t_sub_item (id, order_item_id, name) VALUES (?, ?, ?)",
            sub.getId(), itemId, sub.getName());
        long specId = sub.getId() * 100;
        for (Spec spec : sub.getSpecs()) {
            jdbc.update("INSERT INTO t_spec (id, sub_item_id, spec_key, spec_value) VALUES (?, ?, ?, ?)",
                specId++, sub.getId(), spec.getKey(), spec.getValue());
        }
    }

    private void updateOrder(Order order) {
        jdbc.update("""
            UPDATE t_order SET status = ?, total_amount = ?, total_currency = ?, update_time = ?
            WHERE id = ?
        """, order.getStatus(),
           order.getTotalAmount() != null ? order.getTotalAmount().amount() : null,
           order.getTotalAmount() != null ? order.getTotalAmount().currency() : null,
           LocalDateTime.now(), order.getId());
    }

    private void deleteOrderItem(Long id) {
        jdbc.update("DELETE FROM t_spec WHERE sub_item_id IN (SELECT id FROM t_sub_item WHERE order_item_id = ?)", id);
        jdbc.update("DELETE FROM t_sub_item WHERE order_item_id = ?", id);
        jdbc.update("DELETE FROM t_order_item WHERE id = ?", id);
    }

    private Order loadOrder(Long id) {
        OrderPO mainPO = jdbc.queryForObject(
            "SELECT * FROM t_order WHERE id = ?",
            (rs, rowNum) -> {
                OrderPO po = new OrderPO();
                po.setId(rs.getLong("id"));
                po.setOrderNo(rs.getString("order_no"));
                po.setStatus(rs.getString("status"));
                po.setTotalAmount(rs.getBigDecimal("total_amount"));
                po.setTotalCurrency(rs.getString("total_currency"));
                return po;
            }, id);

        // 加载 items
        List<OrderItem> items = jdbc.query(
            "SELECT * FROM t_order_item WHERE order_id = ?",
            (rs, rowNum) -> {
                Money price = rs.getBigDecimal("unit_price") != null
                    ? new Money(rs.getBigDecimal("unit_price"), rs.getString("unit_currency")) : null;
                OrderItem item = new OrderItem(rs.getLong("id"), rs.getString("sku"),
                    rs.getString("product_name"), rs.getInt("quantity"), price);
                // 加载 subItems
                List<SubItem> subItems = jdbc.query(
                    "SELECT * FROM t_sub_item WHERE order_item_id = ?",
                    (rs2, rn2) -> {
                        SubItem sub = new SubItem(rs2.getLong("id"), rs2.getString("name"));
                        // 加载 specs
                        List<Spec> specs = jdbc.query(
                            "SELECT * FROM t_spec WHERE sub_item_id = ?",
                            (rs3, rn3) -> new Spec(rs3.getString("spec_key"), rs3.getString("spec_value")),
                            sub.getId());
                        sub.getSpecs().addAll(specs);
                        return sub;
                    }, item.getId());
                item.getSubItems().addAll(subItems);
                return item;
            }, id);

        // 加载 customer
        Customer customer = null;
        List<Customer> customers = jdbc.query(
            "SELECT * FROM t_customer WHERE order_id = ?",
            (rs, rowNum) -> {
                Customer c = new Customer(rs.getLong("id"), rs.getString("name"));
                String phone = rs.getString("contact_phone");
                String email = rs.getString("contact_email");
                if (phone != null || email != null) {
                    c.setContact(new ContactInfo(phone, email));
                }
                // 加载 addresses
                List<Address> addresses = jdbc.query(
                    "SELECT * FROM t_address WHERE customer_id = ?",
                    (rs2, rn2) -> new Address(rs2.getLong("id"), rs2.getString("type"), rs2.getString("city")),
                    c.getId());
                c.getAddresses().addAll(addresses);
                return c;
            }, id);
        if (!customers.isEmpty()) {
            customer = customers.get(0);
        }

        Map<String, Object> childData = new HashMap<>();
        childData.put("items", items);
        childData.put("customer", customer);
        return orderConverter.toRoot(mainPO, childData);
    }

    // ==================== 测试用例 ====================

    @Nested
    @DisplayName("ConverterRegistry 测试")
    class ConverterRegistryTest {

        @Test
        @DisplayName("注册并获取简单转换器")
        void shouldRegisterAndGetSimpleConverter() {
            var converter = registry.getConverter(OrderItem.class);

            assertThat(converter).isPresent();
            assertThat(converter.get().domainClass()).isEqualTo(OrderItem.class);
            assertThat(converter.get().poClass()).isEqualTo(OrderItemPO.class);
        }

        @Test
        @DisplayName("注册并获取组合转换器")
        void shouldRegisterAndGetCompositeConverter() {
            var converter = registry.getCompositeConverter(Order.class);

            assertThat(converter).isPresent();
            assertThat(converter.get().rootClass()).isEqualTo(Order.class);
            assertThat(converter.get().mainPoClass()).isEqualTo(OrderPO.class);
        }

        @Test
        @DisplayName("自动扫描子实体转换器")
        void shouldAutoScanChildConverters() {
            Map<String, PoConverter<?, ?>> childConverters = registry.getChildConverters(Order.class);

            assertThat(childConverters).containsKey("items");
            assertThat(childConverters.get("items").domainClass()).isEqualTo(OrderItem.class);
        }

        @Test
        @DisplayName("转换器正确转换 DO ↔ PO")
        void shouldConvertBetweenDOAndPO() {
            OrderItem item = new OrderItem(1L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("50.00")));

            OrderItemPO po = itemConverter.toPO(item);
            assertThat(po.getId()).isEqualTo(1L);
            assertThat(po.getSku()).isEqualTo("SKU-001");

            OrderItem restored = itemConverter.toDomain(po);
            assertThat(restored.getId()).isEqualTo(item.getId());
            assertThat(restored.getSku()).isEqualTo(item.getSku());
        }
    }

    @Nested
    @DisplayName("ChangeTrackerProvider 测试")
    class ChangeTrackerProviderTest {

        @Test
        @DisplayName("创建 ChangeTracker 实例")
        void shouldCreateChangeTracker() {
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            assertThat(changeTracker).isNotNull();
        }

        @Test
        @DisplayName("每次创建独立实例")
        void shouldCreateIndependentInstances() {
            ChangeTracker changeTracker1 = new ChangeTracker(trackingProvider.create());
            ChangeTracker changeTracker2 = new ChangeTracker(trackingProvider.create());
            assertThat(changeTracker1).isNotSameAs(changeTracker2);
        }
    }

    @Nested
    @DisplayName("完整 CRUD 流程测试")
    class CrudFlowTest {

        @Test
        @DisplayName("INSERT: 新增订单及子项")
        void shouldInsertOrderWithItems() {
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            order.setTotalAmount(Money.of(new BigDecimal("100.00")));
            order.getItems().add(new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00"))));
            order.getItems().add(new OrderItem(102L, "SKU-002", "商品B", 1, Money.of(new BigDecimal("40.00"))));

            // 转换并插入
            insertOrder(orderConverter.toMainPO(order));
            for (OrderItem item : order.getItems()) {
                insertOrderItem(item, order.getId());
            }

            // 验证
            Order loaded = loadOrder(1L);
            assertThat(loaded.getStatus()).isEqualTo("PENDING");
            assertThat(loaded.getItems()).hasSize(2);
        }

        @Test
        @DisplayName("UPDATE: 检测字段变更并更新")
        void shouldDetectAndApplyValueChanges() {
            // 准备数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            order.setTotalAmount(Money.of(new BigDecimal("100.00")));
            insertOrder(orderConverter.toMainPO(order));

            // 加载并追踪
            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // 修改
            loaded.setStatus("CONFIRMED");
            loaded.setTotalAmount(Money.of(new BigDecimal("200.00")));

            // 计算变更
            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.isEmpty()).isFalse();

            List<ValueChange> fieldChanges = changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .toList();
            assertThat(fieldChanges).isNotEmpty();

            // 应用变更
            updateOrder(loaded);

            // 验证
            Order reloaded = loadOrder(1L);
            assertThat(reloaded.getStatus()).isEqualTo("CONFIRMED");
            assertThat(reloaded.getTotalAmount().amount()).isEqualByComparingTo(new BigDecimal("200.00"));
        }

        @Test
        @DisplayName("UPDATE: 检测子项字段变更")
        void shouldDetectItemValueChanges() {
            // 准备数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            order.getItems().add(item);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            // 加载并追踪
            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // 修改子项
            loaded.getItems().get(0).setQuantity(5);
            loaded.getItems().get(0).setProductName("商品A（促销版）");

            // 计算变更
            ChangeSet changeSet = changeTracker.calculateChanges();
            List<ValueChange> fieldChanges = changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .toList();

            assertThat(fieldChanges).hasSize(2);
            assertThat(fieldChanges.stream().anyMatch(fc -> fc.path().contains("quantity"))).isTrue();
            assertThat(fieldChanges.stream().anyMatch(fc -> fc.path().contains("productName"))).isTrue();
        }

        @Test
        @DisplayName("INSERT: 检测子项新增")
        void shouldDetectItemAdded() {
            // 准备数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            OrderItem item1 = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            order.getItems().add(item1);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item1, order.getId());

            // 加载并追踪
            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // 新增子项
            OrderItem newItem = new OrderItem(102L, "SKU-002", "商品B", 1, Money.of(new BigDecimal("50.00")));
            loaded.getItems().add(newItem);

            // 计算变更
            ChangeSet changeSet = changeTracker.calculateChanges();
            List<ItemAddedChange> addedChanges = changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ItemAddedChange)
                    .map(c -> (ItemAddedChange) c)
                    .toList();

            assertThat(addedChanges).hasSize(1);
        }

        @Test
        @DisplayName("DELETE: 检测子项删除")
        void shouldDetectItemRemoved() {
            // 准备数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            OrderItem item1 = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            OrderItem item2 = new OrderItem(102L, "SKU-002", "商品B", 1, Money.of(new BigDecimal("50.00")));
            order.getItems().add(item1);
            order.getItems().add(item2);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item1, order.getId());
            insertOrderItem(item2, order.getId());

            // 加载并追踪
            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // 删除子项
            OrderItem toRemove = loaded.getItems().stream()
                    .filter(i -> i.getId().equals(101L))
                    .findFirst().orElseThrow();
            loaded.getItems().remove(toRemove);

            // 计算变更
            ChangeSet changeSet = changeTracker.calculateChanges();
            List<ItemRemovedChange> removedChanges = changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ItemRemovedChange)
                    .map(c -> (ItemRemovedChange) c)
                    .toList();

            assertThat(removedChanges).hasSize(1);
        }

        @Test
        @DisplayName("综合场景: 主表+子表同时变更")
        void shouldHandleComplexChanges() {
            // 准备数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            order.setTotalAmount(Money.of(new BigDecimal("100.00")));
            OrderItem item1 = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            OrderItem item2 = new OrderItem(102L, "SKU-002", "商品B", 1, Money.of(new BigDecimal("40.00")));
            order.getItems().add(item1);
            order.getItems().add(item2);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item1, order.getId());
            insertOrderItem(item2, order.getId());

            // 加载并追踪
            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // 执行多种变更
            loaded.setStatus("CONFIRMED");                           // 主表字段变更
            loaded.getItems().get(0).setQuantity(5);                 // 子项字段变更
            OrderItem toRemove = loaded.getItems().get(1);
            loaded.getItems().remove(toRemove);                      // 删除子项
            OrderItem newItem = new OrderItem(103L, "SKU-003", "商品C", 3, Money.of(new BigDecimal("60.00")));
            loaded.getItems().add(newItem);                          // 新增子项

            // 计算变更
            ChangeSet changeSet = changeTracker.calculateChanges();
            List<Change> changes = changeSet.getLeafChanges();

            long fieldChanges = changes.stream().filter(c -> c instanceof ValueChange).count();
            long itemAdded = changes.stream().filter(c -> c instanceof ItemAddedChange).count();
            long itemRemoved = changes.stream().filter(c -> c instanceof ItemRemovedChange).count();

            assertThat(fieldChanges).isGreaterThanOrEqualTo(2);  // status + quantity
            assertThat(itemAdded).isEqualTo(1);
            assertThat(itemRemoved).isEqualTo(1);
        }

        @Test
        @DisplayName("B01: 无变更时 ChangeSet 为空")
        void shouldReturnEmptyChangeSetWhenNoChanges() {
            // 准备数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            insertOrder(orderConverter.toMainPO(order));

            // 加载并追踪
            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // 不做任何修改
            ChangeSet changeSet = changeTracker.calculateChanges();

            assertThat(changeSet.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("B04: 值对象整体替换")
        void shouldDetectValueObjectReplacement() {
            Order order = new Order(1L, "ORD-001");
            order.setTotalAmount(Money.of(new BigDecimal("100.00")));
            insertOrder(orderConverter.toMainPO(order));

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // 替换值对象
            loaded.setTotalAmount(new Money(new BigDecimal("200.00"), "USD"));

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.isEmpty()).isFalse();
        }
    }

    // ==================== 单实体（一对一）场景测试 ====================

    @Nested
    @DisplayName("单实体场景测试")
    class SingleEntityTest {

        @Test
        @DisplayName("E01: 单实体字段变更")
        void shouldDetectSingleEntityValueChange() {
            Order order = new Order(1L, "ORD-001");
            Customer customer = new Customer(10L, "张三");
            order.setCustomer(customer);
            insertOrder(orderConverter.toMainPO(order));
            insertCustomer(customer, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getCustomer().setName("李四");

            ChangeSet changeSet = changeTracker.calculateChanges();
            List<ValueChange> changes = changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .toList();

            assertThat(changes).anyMatch(fc -> fc.path().contains("customer") && fc.path().contains("name"));
        }

        @Test
        @DisplayName("E02: 单实体嵌套值对象变更")
        void shouldDetectNestedValueObjectChange() {
            Order order = new Order(1L, "ORD-001");
            Customer customer = new Customer(10L, "张三");
            customer.setContact(new ContactInfo("13800138000", "test@example.com"));
            order.setCustomer(customer);
            insertOrder(orderConverter.toMainPO(order));
            insertCustomer(customer, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getCustomer().setContact(new ContactInfo("13900139000", "test@example.com"));

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("E03: 单实体从 null 变有值")
        void shouldDetectSingleEntityFromNullToValue() {
            Order order = new Order(1L, "ORD-001");
            insertOrder(orderConverter.toMainPO(order));

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.setCustomer(new Customer(10L, "张三"));

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("E04: 单实体从有值变 null")
        void shouldDetectSingleEntityFromValueToNull() {
            Order order = new Order(1L, "ORD-001");
            Customer customer = new Customer(10L, "张三");
            order.setCustomer(customer);
            insertOrder(orderConverter.toMainPO(order));
            insertCustomer(customer, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.setCustomer(null);

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.isEmpty()).isFalse();
        }
    }

    // ==================== 深层嵌套场景测试 ====================

    @Nested
    @DisplayName("深层嵌套场景测试")
    class DeepNestedTest {

        @Test
        @DisplayName("N01: 二层嵌套字段变更")
        void shouldDetectSecondLevelValueChange() {
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            order.getItems().add(item);
            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getItems().get(0).setProductName("商品A改");

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .anyMatch(fc -> fc.path().contains("productName"))).isTrue();
        }

        @Test
        @DisplayName("N02: 三层嵌套字段变更")
        void shouldDetectThirdLevelValueChange() {
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            SubItem subItem = new SubItem(1001L, "子项1");
            item.getSubItems().add(subItem);
            order.getItems().add(item);
            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getItems().get(0).getSubItems().get(0).setName("子项1改");

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .anyMatch(fc -> fc.path().contains("subItems") && fc.path().contains("name"))).isTrue();
        }

        @Test
        @DisplayName("N03: 四层嵌套字段变更")
        void shouldDetectFourthLevelValueChange() {
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            SubItem subItem = new SubItem(1001L, "子项1");
            subItem.getSpecs().add(new Spec("颜色", "红色"));
            item.getSubItems().add(subItem);
            order.getItems().add(item);
            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getItems().get(0).getSubItems().get(0).getSpecs().get(0).setValue("蓝色");

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .anyMatch(fc -> fc.path().contains("specs") && fc.path().contains("value"))).isTrue();
        }

        @Test
        @DisplayName("N04: 三层集合新增")
        void shouldDetectThirdLevelCollectionAdd() {
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            order.getItems().add(item);
            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getItems().get(0).getSubItems().add(new SubItem(1001L, "新子项"));

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.getLeafChanges().stream()
                    .anyMatch(c -> c instanceof ItemAddedChange)).isTrue();
        }

        @Test
        @DisplayName("N05: 四层集合新增")
        void shouldDetectFourthLevelCollectionAdd() {
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            SubItem subItem = new SubItem(1001L, "子项1");
            item.getSubItems().add(subItem);
            order.getItems().add(item);
            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getItems().get(0).getSubItems().get(0).getSpecs().add(new Spec("尺寸", "XL"));

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.getLeafChanges().stream()
                    .anyMatch(c -> c instanceof ItemAddedChange)).isTrue();
        }

        @Test
        @DisplayName("N07: 嵌套值对象替换")
        void shouldDetectNestedValueObjectReplacement() {
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            order.getItems().add(item);
            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getItems().get(0).setUnitPrice(Money.of(new BigDecimal("50.00")));

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("N08: 单实体内集合变更")
        void shouldDetectCollectionChangeInSingleEntity() {
            Order order = new Order(1L, "ORD-001");
            Customer customer = new Customer(10L, "张三");
            customer.getAddresses().add(new Address(1L, "HOME", "北京"));
            order.setCustomer(customer);
            insertOrder(orderConverter.toMainPO(order));
            insertCustomer(customer, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getCustomer().getAddresses().add(new Address(2L, "WORK", "上海"));

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.getLeafChanges().stream()
                    .anyMatch(c -> c instanceof ItemAddedChange)).isTrue();
        }
    }

    // ==================== Map 场景测试 ====================

    @Nested
    @DisplayName("Map 场景测试")
    class MapTest {

        @Test
        @DisplayName("M01: Map 新增键值对")
        void shouldDetectMapEntryAdded() {
            Order order = new Order(1L, "ORD-001");
            insertOrder(orderConverter.toMainPO(order));

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getExtensions().put("key1", "value1");

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.getLeafChanges().stream()
                    .anyMatch(c -> c instanceof ItemAddedChange)).isTrue();
        }

        @Test
        @DisplayName("M02: Map 删除键值对")
        void shouldDetectMapEntryRemoved() {
            Order order = new Order(1L, "ORD-001");
            order.getExtensions().put("key1", "value1");
            insertOrder(orderConverter.toMainPO(order));

            Order loaded = loadOrder(1L);
            loaded.getExtensions().put("key1", "value1");
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getExtensions().remove("key1");

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.getLeafChanges().stream()
                    .anyMatch(c -> c instanceof ItemRemovedChange)).isTrue();
        }

        @Test
        @DisplayName("M03: Map 值变更")
        void shouldDetectMapValueChange() {
            Order order = new Order(1L, "ORD-001");
            order.getExtensions().put("key1", "value1");
            insertOrder(orderConverter.toMainPO(order));

            Order loaded = loadOrder(1L);
            loaded.getExtensions().put("key1", "value1");
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getExtensions().put("key1", "value2");

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .anyMatch(fc -> "value2".equals(fc.newValue()))).isTrue();
        }

        @Test
        @DisplayName("M04: 嵌套 Map 变更")
        void shouldDetectNestedMapChange() {
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            item.getAttributes().put("color", "red");
            order.getItems().add(item);
            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            Order loaded = loadOrder(1L);
            loaded.getItems().get(0).getAttributes().put("color", "red");
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getItems().get(0).getAttributes().put("color", "blue");

            ChangeSet changeSet = changeTracker.calculateChanges();
            assertThat(changeSet.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("M05: Map 清空")
        void shouldDetectMapCleared() {
            Order order = new Order(1L, "ORD-001");
            order.getExtensions().put("k1", "v1");
            order.getExtensions().put("k2", "v2");
            insertOrder(orderConverter.toMainPO(order));

            Order loaded = loadOrder(1L);
            loaded.getExtensions().put("k1", "v1");
            loaded.getExtensions().put("k2", "v2");
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getExtensions().clear();

            ChangeSet changeSet = changeTracker.calculateChanges();
            long removedCount = changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ItemRemovedChange)
                    .count();
            assertThat(removedCount).isEqualTo(2);
        }

        @Test
        @DisplayName("M06: 空 Map 到非空")
        void shouldDetectEmptyMapToNonEmpty() {
            Order order = new Order(1L, "ORD-001");
            insertOrder(orderConverter.toMainPO(order));

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            loaded.getExtensions().put("k1", "v1");
            loaded.getExtensions().put("k2", "v2");

            ChangeSet changeSet = changeTracker.calculateChanges();
            long addedCount = changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ItemAddedChange)
                    .count();
            assertThat(addedCount).isEqualTo(2);
        }
    }

    // ==================== 综合复杂场景测试 ====================

    @Nested
    @DisplayName("综合复杂场景测试")
    class ComplexScenarioTest {

        @Test
        @DisplayName("X01: 全层级同时变更")
        void shouldHandleAllLevelChangesSimultaneously() {
            // 准备4层嵌套数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            order.setTotalAmount(Money.of(new BigDecimal("100.00")));

            Customer customer = new Customer(10L, "张三");
            customer.setContact(new ContactInfo("13800138000", "test@example.com"));
            customer.getAddresses().add(new Address(1L, "HOME", "北京"));
            order.setCustomer(customer);

            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            SubItem subItem = new SubItem(1001L, "子项1");
            subItem.getSpecs().add(new Spec("颜色", "红色"));
            item.getSubItems().add(subItem);
            order.getItems().add(item);

            insertOrder(orderConverter.toMainPO(order));
            insertCustomer(customer, order.getId());
            insertOrderItem(item, order.getId());

            // 加载并追踪
            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // 全层级变更（不包含 Map，因为 change-tracking 库暂不支持）
            loaded.setStatus("CONFIRMED");                                          // 第1层
            loaded.getCustomer().setName("李四");                                    // 第2层
            loaded.getItems().get(0).setQuantity(5);                                // 第2层
            loaded.getItems().get(0).getSubItems().get(0).setName("子项1改");        // 第3层
            loaded.getItems().get(0).getSubItems().get(0).getSpecs().get(0).setValue("蓝色"); // 第4层

            ChangeSet changeSet = changeTracker.calculateChanges();
            List<ValueChange> fieldChanges = changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .toList();

            assertThat(fieldChanges.size()).isGreaterThanOrEqualTo(5);
        }

        @Test
        @DisplayName("X02: 多聚合根追踪")
        void shouldTrackMultipleAggregateRoots() {
            Order order1 = new Order(1L, "ORD-001");
            order1.setStatus("PENDING");
            Order order2 = new Order(2L, "ORD-002");
            order2.setStatus("PENDING");

            insertOrder(orderConverter.toMainPO(order1));
            insertOrder(orderConverter.toMainPO(order2));

            Order loaded1 = loadOrder(1L);
            Order loaded2 = loadOrder(2L);

            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded1);
            changeTracker.track(loaded2);

            loaded1.setStatus("CONFIRMED");
            loaded2.setStatus("SHIPPED");

            ChangeSet changeSet = changeTracker.calculateChanges();
            List<ValueChange> changes = changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .toList();

            assertThat(changes).hasSize(2);
        }

        @Test
        @DisplayName("X03: excludeNew 排除")
        void shouldExcludeNewlyRegisteredObjects() {
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            insertOrder(orderConverter.toMainPO(order));

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // 新增的 item 标记为 new
            OrderItem newItem = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            loaded.getItems().add(newItem);
            changeTracker.excludeNew(newItem);

            ChangeSet changeSet = changeTracker.calculateChanges();
            // 新增的对象不应该产生 ValueChange（只有 ItemAddedChange）
            List<ValueChange> fieldChanges = changeSet.getLeafChanges().stream()
                    .filter(c -> c instanceof ValueChange)
                    .map(c -> (ValueChange) c)
                    .filter(fc -> fc.path().contains("items"))
                    .toList();

            assertThat(fieldChanges).isEmpty();
        }
    }

    // ==================== 端到端集成测试（使用 OrderRepository） ====================

    @Nested
    @DisplayName("端到端持久化流程测试")
    class EndToEndFlowTest {

        @Test
        @DisplayName("B01: 无变更时不执行 SQL")
        void shouldNotExecuteSqlWhenNoChanges() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            insertOrder(orderConverter.toMainPO(order));

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded).isNotNull();

            // 不做任何修改，直接保存
            boolean saved = orderRepository.save(loaded);

            assertThat(saved).isFalse();  // 无变更，返回 false
        }

        @Test
        @DisplayName("B02: 主表单字段变更")
        void shouldUpdateMainTableValueChange() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            insertOrder(orderConverter.toMainPO(order));

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getStatus()).isEqualTo("PENDING");

            // 修改状态
            loaded.setStatus("PAID");

            // 保存（自动生成 UPDATE SQL）
            boolean saved = orderRepository.save(loaded);
            assertThat(saved).isTrue();

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getStatus()).isEqualTo("PAID");
        }

        @Test
        @DisplayName("C01: 集合项新增")
        void shouldInsertNewCollectionItem() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            OrderItem item1 = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            order.getItems().add(item1);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item1, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getItems()).hasSize(1);

            // 新增订单项
            OrderItem newItem = new OrderItem(102L, "SKU-002", "商品B", 1, Money.of(new BigDecimal("50.00")));
            loaded.getItems().add(newItem);

            // 保存（自动生成 INSERT SQL）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getItems()).hasSize(2);
            assertThat(reloaded.getItems().stream()
                    .anyMatch(i -> i.getSku().equals("SKU-002"))).isTrue();
        }

        @Test
        @DisplayName("C02: 集合项删除")
        void shouldDeleteCollectionItem() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            OrderItem item1 = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            OrderItem item2 = new OrderItem(102L, "SKU-002", "商品B", 1, Money.of(new BigDecimal("50.00")));
            order.getItems().add(item1);
            order.getItems().add(item2);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item1, order.getId());
            insertOrderItem(item2, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getItems()).hasSize(2);

            // 删除订单项
            OrderItem toRemove = loaded.getItems().stream()
                    .filter(i -> i.getId().equals(101L))
                    .findFirst().orElseThrow();
            loaded.getItems().remove(toRemove);

            // 保存（自动生成 DELETE SQL + 级联删除）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getItems()).hasSize(1);
            assertThat(reloaded.getItems().get(0).getSku()).isEqualTo("SKU-002");
        }

        @Test
        @DisplayName("C03: 集合项字段变更")
        void shouldUpdateCollectionItemField() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            order.getItems().add(item);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getItems().get(0).getQuantity()).isEqualTo(2);

            // 修改订单项数量
            loaded.getItems().get(0).setQuantity(5);

            // 保存（自动生成 UPDATE SQL）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getItems().get(0).getQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("X01: 综合场景-主表+子表同时变更")
        void shouldHandleComplexChanges() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            order.setTotalAmount(Money.of(new BigDecimal("100.00")));
            OrderItem item1 = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            OrderItem item2 = new OrderItem(102L, "SKU-002", "商品B", 1, Money.of(new BigDecimal("40.00")));
            order.getItems().add(item1);
            order.getItems().add(item2);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item1, order.getId());
            insertOrderItem(item2, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);

            // 执行多种变更
            loaded.setStatus("CONFIRMED");                                      // 主表字段变更
            loaded.getItems().get(0).setQuantity(5);                            // 子项字段变更
            OrderItem toRemove = loaded.getItems().get(1);
            loaded.getItems().remove(toRemove);                                  // 删除子项
            OrderItem newItem = new OrderItem(103L, "SKU-003", "商品C", 3, Money.of(new BigDecimal("60.00")));
            loaded.getItems().add(newItem);                                      // 新增子项

            // 保存（自动生成混合 SQL）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getStatus()).isEqualTo("CONFIRMED");
            assertThat(reloaded.getItems()).hasSize(2);
            assertThat(reloaded.getItems().get(0).getQuantity()).isEqualTo(5);
            assertThat(reloaded.getItems().stream()
                    .anyMatch(i -> i.getSku().equals("SKU-003"))).isTrue();
        }

        // ==================== 单实体（一对一）端到端场景 ====================

        @Test
        @DisplayName("E01: 单实体字段变更")
        void shouldUpdateSingleEntityField_E2E() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            Customer customer = new Customer(10L, "张三");
            order.setCustomer(customer);

            insertOrder(orderConverter.toMainPO(order));
            insertCustomer(customer, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getCustomer()).isNotNull();
            assertThat(loaded.getCustomer().getName()).isEqualTo("张三");

            // 修改单实体字段
            loaded.getCustomer().setName("李四");

            // 保存（自动生成 UPDATE SQL）
            boolean saved = orderRepository.save(loaded);
            assertThat(saved).isTrue();

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getCustomer().getName()).isEqualTo("李四");
        }

        @Test
        @DisplayName("E02: 单实体嵌套值对象变更")
        void shouldUpdateNestedValueObjectInSingleEntity_E2E() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            Customer customer = new Customer(10L, "张三");
            customer.setContact(new ContactInfo("13800138000", "test@example.com"));
            order.setCustomer(customer);

            insertOrder(orderConverter.toMainPO(order));
            insertCustomer(customer, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getCustomer().getContact().phone()).isEqualTo("13800138000");

            // 修改嵌套值对象
            loaded.getCustomer().setContact(new ContactInfo("13900139000", "new@example.com"));

            // 保存（自动生成 UPDATE SQL）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getCustomer().getContact().phone()).isEqualTo("13900139000");
            assertThat(reloaded.getCustomer().getContact().email()).isEqualTo("new@example.com");
        }

        @Test
        @DisplayName("E03: 单实体内集合新增")
        void shouldAddItemToSingleEntityCollection_E2E() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            Customer customer = new Customer(10L, "张三");
            customer.getAddresses().add(new Address(1L, "HOME", "北京"));
            order.setCustomer(customer);

            insertOrder(orderConverter.toMainPO(order));
            insertCustomer(customer, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getCustomer().getAddresses()).hasSize(1);

            // 新增地址
            loaded.getCustomer().getAddresses().add(new Address(2L, "WORK", "上海"));

            // 保存（自动生成 INSERT SQL）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getCustomer().getAddresses()).hasSize(2);
            assertThat(reloaded.getCustomer().getAddresses().stream()
                    .anyMatch(a -> a.getCity().equals("上海"))).isTrue();
        }

        @Test
        @DisplayName("E04: 单实体内集合项字段变更")
        void shouldUpdateFieldInSingleEntityCollectionItem_E2E() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            Customer customer = new Customer(10L, "张三");
            customer.getAddresses().add(new Address(1L, "HOME", "北京"));
            order.setCustomer(customer);

            insertOrder(orderConverter.toMainPO(order));
            insertCustomer(customer, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getCustomer().getAddresses().get(0).getCity()).isEqualTo("北京");

            // 修改地址城市
            loaded.getCustomer().getAddresses().get(0).setCity("深圳");

            // 保存（自动生成 UPDATE SQL）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getCustomer().getAddresses().get(0).getCity()).isEqualTo("深圳");
        }

        // ==================== 深层嵌套端到端场景 ====================

        @Test
        @DisplayName("N02: 三层嵌套字段变更")
        void shouldUpdateThirdLevelNestedField_E2E() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            SubItem subItem = new SubItem(1001L, "子项1");
            item.getSubItems().add(subItem);
            order.getItems().add(item);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getItems().get(0).getSubItems().get(0).getName()).isEqualTo("子项1");

            // 修改三层嵌套字段
            loaded.getItems().get(0).getSubItems().get(0).setName("子项1改");

            // 保存（自动生成 UPDATE SQL）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getItems().get(0).getSubItems().get(0).getName()).isEqualTo("子项1改");
        }

        @Test
        @DisplayName("N03: 四层嵌套字段变更")
        void shouldUpdateFourthLevelNestedField_E2E() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            SubItem subItem = new SubItem(1001L, "子项1");
            subItem.getSpecs().add(new Spec("颜色", "红色"));
            item.getSubItems().add(subItem);
            order.getItems().add(item);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getItems().get(0).getSubItems().get(0).getSpecs().get(0).getValue()).isEqualTo("红色");

            // 修改四层嵌套字段
            loaded.getItems().get(0).getSubItems().get(0).getSpecs().get(0).setValue("蓝色");

            // 保存（自动生成 UPDATE SQL）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getItems().get(0).getSubItems().get(0).getSpecs().get(0).getValue()).isEqualTo("蓝色");
        }

        @Test
        @DisplayName("N04: 三层集合新增")
        void shouldAddThirdLevelCollectionItem_E2E() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            order.getItems().add(item);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getItems().get(0).getSubItems()).isEmpty();

            // 新增三层子项
            loaded.getItems().get(0).getSubItems().add(new SubItem(1001L, "新子项"));

            // 保存（自动生成 INSERT SQL）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getItems().get(0).getSubItems()).hasSize(1);
            assertThat(reloaded.getItems().get(0).getSubItems().get(0).getName()).isEqualTo("新子项");
        }

        @Test
        @DisplayName("N05: 四层集合新增")
        void shouldAddFourthLevelCollectionItem_E2E() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            SubItem subItem = new SubItem(1001L, "子项1");
            item.getSubItems().add(subItem);
            order.getItems().add(item);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getItems().get(0).getSubItems().get(0).getSpecs()).isEmpty();

            // 新增四层规格
            loaded.getItems().get(0).getSubItems().get(0).getSpecs().add(new Spec("尺寸", "XL"));

            // 保存（自动生成 INSERT SQL）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getItems().get(0).getSubItems().get(0).getSpecs()).hasSize(1);
            assertThat(reloaded.getItems().get(0).getSubItems().get(0).getSpecs().get(0).getValue()).isEqualTo("XL");
        }

        @Test
        @DisplayName("N07: 嵌套值对象替换")
        void shouldReplaceNestedValueObject_E2E() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            order.getItems().add(item);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getItems().get(0).getUnitPrice().amount()).isEqualByComparingTo(new BigDecimal("30.00"));

            // 替换嵌套值对象
            loaded.getItems().get(0).setUnitPrice(Money.of(new BigDecimal("50.00")));

            // 保存（自动生成 UPDATE SQL）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getItems().get(0).getUnitPrice().amount()).isEqualByComparingTo(new BigDecimal("50.00"));
        }

        // ==================== Map 端到端场景 ====================
        // 注意：Map 的持久化需要 JSON 序列化，当前测试框架未实现

        @Test
        @Disabled("Map 持久化需要 JSON 序列化，当前框架未实现")
        @DisplayName("M01: Map 新增键值对")
        void shouldAddMapEntry_E2E() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            insertOrder(orderConverter.toMainPO(order));

            // 通过 Repository 加载
            Order loaded = orderRepository.getByID(1L);
            assertThat(loaded.getExtensions()).isEmpty();

            // 新增 Map 键值对
            loaded.getExtensions().put("key1", "value1");
            loaded.getExtensions().put("key2", "value2");

            // 保存（自动生成 INSERT SQL）
            orderRepository.save(loaded);

            // 重新加载验证
            Order reloaded = orderRepository.getByID(1L);
            assertThat(reloaded.getExtensions()).hasSize(2);
            assertThat(reloaded.getExtensions().get("key1")).isEqualTo("value1");
        }

        @Test
        @Disabled("Map 持久化需要 JSON 序列化，当前框架未实现")
        @DisplayName("M03: Map 值变更")
        void shouldUpdateMapValue_E2E() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            order.getExtensions().put("key1", "value1");
            insertOrder(orderConverter.toMainPO(order));

            // 通过 Repository 加载（需要手动初始化 extensions，因为 loadOrder 不加载 Map）
            Order loaded = orderRepository.getByID(1L);
            loaded.getExtensions().put("key1", "value1");  // 模拟从 JSON 加载

            // 修改 Map 值
            loaded.getExtensions().put("key1", "value2");

            // 保存（自动生成 UPDATE SQL）
            orderRepository.save(loaded);

            // 重新加载验证（同样需要手动验证，因为 loadOrder 不加载 Map）
            Order reloaded = orderRepository.getByID(1L);
            // 注意：实际项目中 extensions 应存储为 JSON，这里简化测试
        }

        @Test
        @Disabled("Map 持久化需要 JSON 序列化，当前框架未实现")
        @DisplayName("M04: 嵌套 Map 变更")
        void shouldUpdateNestedMap_E2E() {
            // 准备初始数据
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "商品A", 2, Money.of(new BigDecimal("30.00")));
            item.getAttributes().put("color", "red");
            order.getItems().add(item);

            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            // 通过 Repository 加载（需要手动初始化 attributes）
            Order loaded = orderRepository.getByID(1L);
            loaded.getItems().get(0).getAttributes().put("color", "red");  // 模拟从 JSON 加载

            // 修改嵌套 Map
            loaded.getItems().get(0).getAttributes().put("color", "blue");

            // 保存（自动生成 UPDATE SQL）
            orderRepository.save(loaded);

            // 验证 ChangeSet 生成
            // 注意：实际项目中 attributes 应存储为 JSON
        }
    }

    // ==================== PoReconstructor 集成测试 ====================

    @Nested
    @DisplayName("PoReconstructor 集成测试")
    class PoReconstructorIntegrationTest {

        private com.nona.inf.persistence.dispatcher.ChangeDispatcher changeDispatcher;
        private com.nona.inf.persistence.reconstructor.PoReconstructor poReconstructor;

        @BeforeEach
        void setUpReconstructor() {
            changeDispatcher = new com.nona.inf.persistence.dispatcher.ChangeDispatcher(registry);
            poReconstructor = new com.nona.inf.persistence.reconstructor.PoReconstructor(registry, changeDispatcher);
        }

        @Test
        @DisplayName("主表变更应该返回主表 PO")
        void shouldReconstructMainPo() {
            // Given
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            insertOrder(orderConverter.toMainPO(order));

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // When
            loaded.setStatus("PAID");
            ChangeSet changeSet = changeTracker.calculateChanges();
            var result = poReconstructor.reconstruct(loaded, changeSet);

            // Then
            assertThat(result.getToSave(OrderPO.class)).hasSize(1);
            assertThat(result.getToSave(OrderPO.class).get(0).getStatus()).isEqualTo("PAID");
        }

        @Test
        @DisplayName("子表新增应该返回新增的 PO")
        void shouldReconstructAddedChildPo() {
            // Given
            Order order = new Order(1L, "ORD-001");
            insertOrder(orderConverter.toMainPO(order));

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // When
            OrderItem newItem = new OrderItem(101L, "SKU-001", "iPhone", 1, Money.of(new java.math.BigDecimal("5999")));
            loaded.getItems().add(newItem);
            ChangeSet changeSet = changeTracker.calculateChanges();
            var result = poReconstructor.reconstruct(loaded, changeSet);

            // Then
            assertThat(result.getToSave(OrderItemPO.class)).hasSize(1);
            assertThat(result.getToSave(OrderItemPO.class).get(0).getProductName()).isEqualTo("iPhone");
        }

        @Test
        @DisplayName("子表删除应该返回删除 ID")
        void shouldReconstructDeletedChildId() {
            // Given
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "iPhone", 1, Money.of(new java.math.BigDecimal("5999")));
            order.getItems().add(item);
            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // When
            loaded.getItems().clear();
            ChangeSet changeSet = changeTracker.calculateChanges();
            var result = poReconstructor.reconstruct(loaded, changeSet);

            // Then
            assertThat(result.getToDeleteIds(OrderItemPO.class)).hasSize(1);
            assertThat(result.getToDeleteIds(OrderItemPO.class).get(0)).isEqualTo(101L);
        }

        @Test
        @DisplayName("子表字段变更应该返回更新的 PO")
        void shouldReconstructUpdatedChildPo() {
            // Given
            Order order = new Order(1L, "ORD-001");
            OrderItem item = new OrderItem(101L, "SKU-001", "iPhone", 1, Money.of(new java.math.BigDecimal("5999")));
            order.getItems().add(item);
            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // When
            loaded.getItems().get(0).setQuantity(3);
            ChangeSet changeSet = changeTracker.calculateChanges();
            var result = poReconstructor.reconstruct(loaded, changeSet);

            // Then
            assertThat(result.getToSave(OrderItemPO.class)).hasSize(1);
            assertThat(result.getToSave(OrderItemPO.class).get(0).getQuantity()).isEqualTo(3);
        }

        @Test
        @DisplayName("混合变更应该返回所有变更的 PO")
        void shouldReconstructMixedChanges() {
            // Given
            Order order = new Order(1L, "ORD-001");
            order.setStatus("PENDING");
            OrderItem item1 = new OrderItem(101L, "SKU-001", "iPhone", 1, Money.of(new java.math.BigDecimal("5999")));
            OrderItem item2 = new OrderItem(102L, "SKU-002", "iPad", 1, Money.of(new java.math.BigDecimal("3999")));
            order.getItems().add(item1);
            order.getItems().add(item2);
            insertOrder(orderConverter.toMainPO(order));
            insertOrderItem(item1, order.getId());
            insertOrderItem(item2, order.getId());

            Order loaded = loadOrder(1L);
            ChangeTracker changeTracker = new ChangeTracker(trackingProvider.create());
            changeTracker.track(loaded);

            // When: 主表变更 + 子表更新 + 子表删除 + 子表新增
            loaded.setStatus("PAID");
            loaded.getItems().get(0).setQuantity(2);
            loaded.getItems().remove(1);
            loaded.getItems().add(new OrderItem(103L, "SKU-003", "MacBook", 1, Money.of(new java.math.BigDecimal("9999"))));
            ChangeSet changeSet = changeTracker.calculateChanges();
            var result = poReconstructor.reconstruct(loaded, changeSet);

            // Then
            // 主表
            assertThat(result.getToSave(OrderPO.class)).hasSize(1);
            assertThat(result.getToSave(OrderPO.class).get(0).getStatus()).isEqualTo("PAID");
            // 子表 toSave: 更新的 item1 + 新增的 item3 = 2
            assertThat(result.getToSave(OrderItemPO.class)).hasSize(2);
            // 子表 toDelete: 删除的 item2 = 1
            assertThat(result.getToDeleteIds(OrderItemPO.class)).hasSize(1);
            assertThat(result.getToDeleteIds(OrderItemPO.class).get(0)).isEqualTo(102L);
        }
    }

    // ==================== InMemoryOrderPORepository ====================

    /**
     * 内存中的 OrderPO Repository 实现（模拟 Spring Data JPA）
     */
    static class InMemoryOrderPORepository implements org.springframework.data.repository.ListCrudRepository<OrderPO, Long> {
        private final JdbcTemplate jdbc;
        private final OrderConverter converter;

        InMemoryOrderPORepository(JdbcTemplate jdbc, OrderConverter converter) {
            this.jdbc = jdbc;
            this.converter = converter;
        }

        @Override
        public <S extends OrderPO> S save(S entity) {
            // H2 使用 MERGE 语法（相当于 MySQL 的 INSERT ... ON DUPLICATE KEY UPDATE）
            jdbc.update("""
                MERGE INTO t_order (id, order_no, status, total_amount, total_currency, tenant_id, create_time, update_time)
                KEY (id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, entity.getId(), entity.getOrderNo(), entity.getStatus(), entity.getTotalAmount(),
                   entity.getTotalCurrency(), entity.getTenantID(), entity.getCreateTime(), entity.getUpdateTime());
            return entity;
        }

        @Override
        public <S extends OrderPO> List<S> saveAll(Iterable<S> entities) {
            List<S> result = new ArrayList<>();
            entities.forEach(e -> result.add(save(e)));
            return result;
        }

        @Override
        public Optional<OrderPO> findById(Long id) {
            List<OrderPO> results = jdbc.query(
                    "SELECT * FROM t_order WHERE id = ?",
                    (rs, rowNum) -> {
                        OrderPO po = new OrderPO();
                        po.setId(rs.getLong("id"));
                        po.setOrderNo(rs.getString("order_no"));
                        po.setStatus(rs.getString("status"));
                        po.setTotalAmount(rs.getBigDecimal("total_amount"));
                        po.setTotalCurrency(rs.getString("total_currency"));
                        po.setTenantID(rs.getString("tenant_id"));
                        return po;
                    }, id);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        }

        @Override
        public boolean existsById(Long id) {
            return findById(id).isPresent();
        }

        @Override
        public List<OrderPO> findAll() {
            return jdbc.query("SELECT * FROM t_order", (rs, rowNum) -> {
                OrderPO po = new OrderPO();
                po.setId(rs.getLong("id"));
                po.setOrderNo(rs.getString("order_no"));
                po.setStatus(rs.getString("status"));
                return po;
            });
        }

        @Override
        public List<OrderPO> findAllById(Iterable<Long> ids) {
            List<OrderPO> result = new ArrayList<>();
            ids.forEach(id -> findById(id).ifPresent(result::add));
            return result;
        }

        @Override
        public long count() {
            return jdbc.queryForObject("SELECT COUNT(*) FROM t_order", Long.class);
        }

        @Override
        public void deleteById(Long id) {
            jdbc.update("DELETE FROM t_order WHERE id = ?", id);
        }

        @Override
        public void delete(OrderPO entity) {
            deleteById(entity.getId());
        }

        @Override
        public void deleteAllById(Iterable<? extends Long> ids) {
            ids.forEach(this::deleteById);
        }

        @Override
        public void deleteAll(Iterable<? extends OrderPO> entities) {
            entities.forEach(this::delete);
        }

        @Override
        public void deleteAll() {
            jdbc.update("DELETE FROM t_order");
        }
    }
}
