package com.nona.inf.persistence.integration;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.inf.persistence.converters.ConverterRegistry;
import com.nona.inf.persistence.converters.PoConverter;
import com.nona.inf.persistence.converters.RdbGeneralConvertor;
import com.nona.inf.persistence.repository.DifferRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * Order 聚合根的仓储实现
 * <p>
 * 继承 DifferRepository，实现完整的变更追踪和持久化
 */
@ScaffoldGenerated
class OrderRepository extends DifferRepository<FullIntegrationTest.Order, FullIntegrationTest.OrderPO, Map<String, Object>> {

    private final JdbcTemplate jdbc;
    private final ConverterRegistry converterRegistry;

    public OrderRepository(
            ListCrudRepository<FullIntegrationTest.OrderPO, Long> repository,
            RdbGeneralConvertor<FullIntegrationTest.Order, FullIntegrationTest.OrderPO, Map<String, Object>> convertor,
            ChangeTrackerProvider changeTrackerProvider,
            JdbcTemplate jdbc,
            ConverterRegistry converterRegistry) {
        super(repository, convertor, changeTrackerProvider);
        this.jdbc = jdbc;
        this.converterRegistry = converterRegistry;
    }

    @Override
    protected Long retrieveIDFromRoot(FullIntegrationTest.Order root) {
        return root.getId();
    }

    @Override
    protected Map<String, Object> getOther(FullIntegrationTest.OrderPO po) {
        Map<String, Object> childData = new HashMap<>();

        // 加载 items
        List<FullIntegrationTest.OrderItem> items = jdbc.query(
                "SELECT * FROM t_order_item WHERE order_id = ?",
                (rs, rowNum) -> {
                    FullIntegrationTest.Money price = rs.getBigDecimal("unit_price") != null
                            ? new FullIntegrationTest.Money(rs.getBigDecimal("unit_price"), rs.getString("unit_currency")) : null;
                    FullIntegrationTest.OrderItem item = new FullIntegrationTest.OrderItem(
                            rs.getLong("id"),
                            rs.getString("sku"),
                            rs.getString("product_name"),
                            rs.getInt("quantity"),
                            price);

                    // 加载 subItems
                    List<FullIntegrationTest.SubItem> subItems = jdbc.query(
                            "SELECT * FROM t_sub_item WHERE order_item_id = ?",
                            (rs2, rn2) -> {
                                FullIntegrationTest.SubItem sub = new FullIntegrationTest.SubItem(
                                        rs2.getLong("id"),
                                        rs2.getString("name"));

                                // 加载 specs
                                List<FullIntegrationTest.Spec> specs = jdbc.query(
                                        "SELECT * FROM t_spec WHERE sub_item_id = ?",
                                        (rs3, rn3) -> new FullIntegrationTest.Spec(
                                                rs3.getString("spec_key"),
                                                rs3.getString("spec_value")),
                                        sub.getId());
                                sub.getSpecs().addAll(specs);
                                return sub;
                            }, item.getId());
                    item.getSubItems().addAll(subItems);
                    return item;
                }, po.getId());

        // 加载 customer
        FullIntegrationTest.Customer customer = null;
        List<FullIntegrationTest.Customer> customers = jdbc.query(
                "SELECT * FROM t_customer WHERE order_id = ?",
                (rs, rowNum) -> {
                    FullIntegrationTest.Customer c = new FullIntegrationTest.Customer(
                            rs.getLong("id"),
                            rs.getString("name"));
                    String phone = rs.getString("contact_phone");
                    String email = rs.getString("contact_email");
                    if (phone != null || email != null) {
                        c.setContact(new FullIntegrationTest.ContactInfo(phone, email));
                    }

                    // 加载 addresses
                    List<FullIntegrationTest.Address> addresses = jdbc.query(
                            "SELECT * FROM t_address WHERE customer_id = ?",
                            (rs2, rn2) -> new FullIntegrationTest.Address(
                                    rs2.getLong("id"),
                                    rs2.getString("type"),
                                    rs2.getString("city")),
                            c.getId());
                    c.getAddresses().addAll(addresses);
                    return c;
                }, po.getId());
        if (!customers.isEmpty()) {
            customer = customers.get(0);
        }

        childData.put("items", items);
        childData.put("customer", customer);
        return childData;
    }

    // ==================== doInsert 实现 ====================

    @Override
    protected void doInsert(FullIntegrationTest.Order root) {
        // 插入主表
        FullIntegrationTest.OrderPO po = convertor.convertToPO(root);
        repository.save(po);

        // 插入子表：items
        for (FullIntegrationTest.OrderItem item : root.getItems()) {
            insertOrderItem(item, root.getId());
        }

        // 插入子表：customer
        if (root.getCustomer() != null) {
            insertCustomer(root.getCustomer(), root.getId());
        }
    }

    private void insertOrderItem(FullIntegrationTest.OrderItem item, Long orderId) {
        jdbc.update("""
            INSERT INTO t_order_item (id, order_id, sku, product_name, quantity, unit_price, unit_currency)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, item.getId(), orderId, item.getSku(), item.getProductName(), item.getQuantity(),
           item.getUnitPrice() != null ? item.getUnitPrice().amount() : null,
           item.getUnitPrice() != null ? item.getUnitPrice().currency() : null);

        // 插入 subItems
        for (FullIntegrationTest.SubItem sub : item.getSubItems()) {
            insertSubItem(sub, item.getId());
        }
    }

    private void insertSubItem(FullIntegrationTest.SubItem sub, Long itemId) {
        jdbc.update("INSERT INTO t_sub_item (id, order_item_id, name) VALUES (?, ?, ?)",
                sub.getId(), itemId, sub.getName());

        long specId = sub.getId() * 100;
        for (FullIntegrationTest.Spec spec : sub.getSpecs()) {
            jdbc.update("INSERT INTO t_spec (id, sub_item_id, spec_key, spec_value) VALUES (?, ?, ?, ?)",
                    specId++, sub.getId(), spec.getKey(), spec.getValue());
        }
    }

    private void insertCustomer(FullIntegrationTest.Customer customer, Long orderId) {
        jdbc.update("""
            INSERT INTO t_customer (id, order_id, name, contact_phone, contact_email)
            VALUES (?, ?, ?, ?, ?)
        """, customer.getId(), orderId, customer.getName(),
           customer.getContact() != null ? customer.getContact().phone() : null,
           customer.getContact() != null ? customer.getContact().email() : null);

        for (FullIntegrationTest.Address addr : customer.getAddresses()) {
            jdbc.update("INSERT INTO t_address (id, customer_id, type, city) VALUES (?, ?, ?, ?)",
                    addr.getId(), customer.getId(), addr.getType(), addr.getCity());
        }
    }

    // ==================== doUpdate 实现 ====================

    @Override
    protected void doUpdate(FullIntegrationTest.Order root, ChangeSet changeSet) {
        Map<String, PoConverter<?, ?>> childConverters = converterRegistry.getChildConverters(FullIntegrationTest.Order.class);

        // 分类变更
        List<Change> mainTableChanges = new ArrayList<>();
        Map<String, List<Change>> childFieldChanges = new HashMap<>();
        Map<String, List<ItemAddedChange>> additions = new HashMap<>();
        Map<String, List<ItemRemovedChange>> removals = new HashMap<>();

        for (Change change : changeSet.getLeafChanges()) {
            String path = change.path();
            String rootField = extractRootFieldName(path);

            if (childConverters.containsKey(rootField)) {
                categorizeChildChange(change, rootField, childFieldChanges, additions, removals);
            } else {
                if (change instanceof ValueChange vc) {
                    mainTableChanges.add(vc);
                } else if (change instanceof ObjectFieldChange ofc) {
                    // 对象/集合字段整体替换（跨类型变化）：ValueNode 承载，无业务值
                    mainTableChanges.add(ofc);
                }
            }
        }

        // 1. 处理主表字段变更
        if (!mainTableChanges.isEmpty()) {
            handleMainTableChanges(root, mainTableChanges);
        }

        // 2. 处理子表删除（先删后增，避免主键冲突）
        for (Map.Entry<String, List<ItemRemovedChange>> entry : removals.entrySet()) {
            handleChildRemovals(root, entry.getKey(), entry.getValue());
        }

        // 3. 处理子表新增
        for (Map.Entry<String, List<ItemAddedChange>> entry : additions.entrySet()) {
            handleChildAdditions(root, entry.getKey(), entry.getValue());
        }

        // 4. 处理子表字段变更
        for (Map.Entry<String, List<Change>> entry : childFieldChanges.entrySet()) {
            handleChildFieldChanges(root, entry.getValue());
        }
    }

    private void categorizeChildChange(
            Change change, String rootField,
            Map<String, List<Change>> childFieldChanges,
            Map<String, List<ItemAddedChange>> additions,
            Map<String, List<ItemRemovedChange>> removals) {
        switch (change) {
            case ItemAddedChange iac -> additions.computeIfAbsent(rootField, k -> new ArrayList<>()).add(iac);
            case ItemRemovedChange irc -> removals.computeIfAbsent(rootField, k -> new ArrayList<>()).add(irc);
            case ValueChange vc -> childFieldChanges.computeIfAbsent(rootField, k -> new ArrayList<>()).add(vc);
            case ObjectFieldChange ofc -> childFieldChanges.computeIfAbsent(rootField, k -> new ArrayList<>()).add(ofc);
            default -> {}
        }
    }

    private void handleMainTableChanges(FullIntegrationTest.Order root, List<Change> changes) {
        StringBuilder sql = new StringBuilder("UPDATE t_order SET ");
        List<Object> params = new ArrayList<>();
        int fieldCount = 0;

        for (Change change : changes) {
            if (change instanceof ObjectFieldChange ofc) {
                handleMainTableObjectFieldChange(root, ofc);
                continue;
            }
            ValueChange vc = (ValueChange) change;
            String fieldName = vc.path();

            if (fieldName.startsWith("customer")) {
                handleCustomerFieldChange(root, fieldName, vc);
                continue;
            }
            if (fieldName.contains("[")) {
                continue;
            }

            if (fieldCount > 0) sql.append(", ");

            if ("status".equals(fieldName)) {
                sql.append("status = ?");
                params.add(vc.newValue());
                fieldCount++;
            } else if ("totalAmount".equals(fieldName)) {
                sql.append("total_amount = ?, total_currency = ?");
                FullIntegrationTest.Money money = (FullIntegrationTest.Money) vc.newValue();
                params.add(money != null ? money.amount() : null);
                params.add(money != null ? money.currency() : null);
                fieldCount++;
            } else if ("orderNo".equals(fieldName)) {
                sql.append("order_no = ?");
                params.add(vc.newValue());
                fieldCount++;
            }
        }

        if (fieldCount == 0) return;

        sql.append(" WHERE id = ?");
        params.add(root.getId());
        jdbc.update(sql.toString(), params.toArray());
    }

    /**
     * 处理主表对象/集合字段整体替换（ObjectFieldChange）。
     * <p>
     * newNode 为 ValueNode 表示：NullNode=清空；PrimitiveNode=值；
     * ObjectNode/CollectionNode/ArrayNode 无法表达为单列业务值，跳过（不炸库）。
     */
    private void handleMainTableObjectFieldChange(FullIntegrationTest.Order root, ObjectFieldChange ofc) {
        if (!"customer".equals(ofc.path())) {
            return;
        }
        if (ofc.newNode() instanceof NullNode) {
            // 客户整体置空：删除客户及其地址子表数据
            jdbc.update("DELETE FROM t_address WHERE customer_id IN (SELECT id FROM t_customer WHERE order_id = ?)", root.getId());
            jdbc.update("DELETE FROM t_customer WHERE order_id = ?", root.getId());
        }
    }

    private void handleChildAdditions(FullIntegrationTest.Order root, String fieldName, List<ItemAddedChange> additions) {
        if ("items".equals(fieldName)) {
            for (ItemAddedChange change : additions) {
                String path = change.path();
                if (path.contains(".subItems") || path.contains(".specs")) {
                    handleNestedCollectionAddition(root, path, change);
                } else {
                    ObjectNode itemNode = (ObjectNode) change.addedItem();
                    Object identifier = itemNode.identifier();
                    FullIntegrationTest.OrderItem item = root.getItems().stream()
                            .filter(i -> i.getId().equals(identifier))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("聚合根中找不到 identifier=" + identifier));
                    insertOrderItem(item, root.getId());
                }
            }
        } else if ("customer".equals(fieldName)) {
            for (ItemAddedChange change : additions) {
                String path = change.path();
                if (path.contains(".addresses")) {
                    handleCustomerAddressAddition(root, change);
                } else {
                    if (root.getCustomer() != null) {
                        insertCustomer(root.getCustomer(), root.getId());
                    }
                }
            }
        }
    }

    private void handleNestedCollectionAddition(FullIntegrationTest.Order root, String path, ItemAddedChange change) {
        if (path.contains(".specs")) {
            ObjectNode specNode = (ObjectNode) change.addedItem();
            String specKey = (String) specNode.identifier();
            for (FullIntegrationTest.OrderItem item : root.getItems()) {
                for (FullIntegrationTest.SubItem subItem : item.getSubItems()) {
                    FullIntegrationTest.Spec spec = subItem.getSpecs().stream()
                            .filter(s -> s.getKey().equals(specKey))
                            .findFirst().orElse(null);
                    if (spec != null) {
                        long specId = subItem.getId() * 100 + subItem.getSpecs().indexOf(spec);
                        jdbc.update("INSERT INTO t_spec (id, sub_item_id, spec_key, spec_value) VALUES (?, ?, ?, ?)",
                                specId, subItem.getId(), spec.getKey(), spec.getValue());
                        return;
                    }
                }
            }
        } else if (path.contains(".subItems")) {
            ObjectNode subItemNode = (ObjectNode) change.addedItem();
            Long subItemId = (Long) subItemNode.identifier();
            for (FullIntegrationTest.OrderItem item : root.getItems()) {
                FullIntegrationTest.SubItem subItem = item.getSubItems().stream()
                        .filter(s -> s.getId().equals(subItemId))
                        .findFirst().orElse(null);
                if (subItem != null) {
                    insertSubItem(subItem, item.getId());
                    return;
                }
            }
        }
    }

    private void handleCustomerAddressAddition(FullIntegrationTest.Order root, ItemAddedChange change) {
        if (root.getCustomer() == null) return;
        ObjectNode addressNode = (ObjectNode) change.addedItem();
        Long addressId = (Long) addressNode.identifier();
        FullIntegrationTest.Address address = root.getCustomer().getAddresses().stream()
                .filter(a -> a.getId().equals(addressId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("customer.addresses 中找不到 identifier=" + addressId));
        jdbc.update("INSERT INTO t_address (id, customer_id, type, city) VALUES (?, ?, ?, ?)",
                address.getId(), root.getCustomer().getId(), address.getType(), address.getCity());
    }

    private void handleChildRemovals(FullIntegrationTest.Order root, String fieldName, List<ItemRemovedChange> removals) {
        if ("items".equals(fieldName)) {
            for (ItemRemovedChange change : removals) {
                ObjectNode itemNode = (ObjectNode) change.removedItem();
                Long itemId = (Long) itemNode.identifier();
                jdbc.update("DELETE FROM t_spec WHERE sub_item_id IN (SELECT id FROM t_sub_item WHERE order_item_id = ?)", itemId);
                jdbc.update("DELETE FROM t_sub_item WHERE order_item_id = ?", itemId);
                jdbc.update("DELETE FROM t_order_item WHERE id = ?", itemId);
            }
        } else if ("customer".equals(fieldName)) {
            jdbc.update("DELETE FROM t_address WHERE customer_id IN (SELECT id FROM t_customer WHERE order_id = ?)", root.getId());
            jdbc.update("DELETE FROM t_customer WHERE order_id = ?", root.getId());
        }
    }

    private void handleChildFieldChanges(FullIntegrationTest.Order root, List<Change> changes) {
        for (Change change : changes) {
            if (!(change instanceof ValueChange vc)) {
                // ObjectFieldChange：子表对象/集合字段整体替换，无业务值可提取，暂不处理（保持既有行为）
                continue;
            }
            String path = vc.path();
            if (path.startsWith("items[")) {
                handleOrderItemFieldChange(root, path, vc);
            } else if (path.startsWith("customer.")) {
                handleCustomerFieldChange(root, path, vc);
            }
        }
    }

    private void handleOrderItemFieldChange(FullIntegrationTest.Order root, String path, ValueChange vc) {
        String[] parts = path.split("\\.");
        Long itemId = extractId(parts[0]);

        if (parts.length == 2) {
            String field = parts[1];
            if ("quantity".equals(field)) {
                jdbc.update("UPDATE t_order_item SET quantity = ? WHERE id = ?", vc.newValue(), itemId);
            } else if ("productName".equals(field)) {
                jdbc.update("UPDATE t_order_item SET product_name = ? WHERE id = ?", vc.newValue(), itemId);
            } else if ("unitPrice".equals(field)) {
                FullIntegrationTest.Money money = (FullIntegrationTest.Money) vc.newValue();
                jdbc.update("UPDATE t_order_item SET unit_price = ?, unit_currency = ? WHERE id = ?",
                        money.amount(), money.currency(), itemId);
            }
        } else if (parts.length >= 3 && parts[1].startsWith("subItems[")) {
            Long subItemId = extractId(parts[1]);
            if (parts.length == 3) {
                if ("name".equals(parts[2])) {
                    jdbc.update("UPDATE t_sub_item SET name = ? WHERE id = ?", vc.newValue(), subItemId);
                }
            } else if (parts.length == 4 && parts[2].startsWith("specs[")) {
                String specKey = extractStringId(parts[2]);
                if ("value".equals(parts[3])) {
                    jdbc.update("UPDATE t_spec SET spec_value = ? WHERE sub_item_id = ? AND spec_key = ?",
                            vc.newValue(), subItemId, specKey);
                }
            }
        }
    }

    private void handleCustomerFieldChange(FullIntegrationTest.Order root, String path, ValueChange vc) {
        if ("customer.name".equals(path)) {
            jdbc.update("UPDATE t_customer SET name = ? WHERE order_id = ?", vc.newValue(), root.getId());
        } else if (path.startsWith("customer.contact")) {
            FullIntegrationTest.ContactInfo contact = (FullIntegrationTest.ContactInfo) vc.newValue();
            jdbc.update("UPDATE t_customer SET contact_phone = ?, contact_email = ? WHERE order_id = ?",
                    contact != null ? contact.phone() : null,
                    contact != null ? contact.email() : null,
                    root.getId());
        } else if (path.startsWith("customer.addresses[")) {
            String[] parts = path.split("\\.");
            Long addressId = extractId(parts[1]);
            if ("city".equals(parts[2])) {
                jdbc.update("UPDATE t_address SET city = ? WHERE id = ?", vc.newValue(), addressId);
            }
        }
    }

    private Long extractId(String idxPart) {
        int start = idxPart.indexOf('[');
        int end = idxPart.indexOf(']');
        return Long.parseLong(idxPart.substring(start + 1, end));
    }

    private String extractStringId(String idxPart) {
        int start = idxPart.indexOf('[');
        int end = idxPart.indexOf(']');
        return idxPart.substring(start + 1, end);
    }

    /**
     * 提取路径的根字段名
     * <p>
     * 示例：items[101].quantity → items
     */
    private String extractRootFieldName(String path) {
        int bracketIdx = path.indexOf('[');
        int dotIdx = path.indexOf('.');
        if (bracketIdx == -1 && dotIdx == -1) return path;
        if (bracketIdx == -1) return path.substring(0, dotIdx);
        if (dotIdx == -1) return path.substring(0, bracketIdx);
        return path.substring(0, Math.min(bracketIdx, dotIdx));
    }
}
