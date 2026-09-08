package com.nona.inf.persistence.converters;

import com.nona.domain.order.entity.OrderItem;
import com.nona.inf.persistence.po.order.OrderItemPO;
import com.nona.util.JacksonUtil;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 订单项快照实体 ↔ 订单项 PO 转换器（order_item 表单行映射）。
 * <p>
 * 高频字段逐列一一对应；JSON 扩展列（spec_attributes/custom_attributes）
 * 以 Jackson 序列化保序 Map（JacksonUtil 同 ProductConvertor 先例），
 * 空 Map 序列化为 {@code {}}、null 集合按 PO 可空列写 null（与领域
 * 「null 集合按不可变空集合处理」的读路径语义对齐）。
 * <p>
 * <b>id 契约</b>：OrderItem 为聚合内快照实体（领域无标识字段），toPO
 * 不承载行 id 赋值（保持 null，行主键由落库路径生成——接线 WU 面）；
 * toDomain 忽略 PO 行 id。
 *
 * @author nona9961
 */
@Component
public class OrderItemConvertor implements PoConverter<OrderItem, OrderItemPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<OrderItem> domainClass() {
        return OrderItem.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<OrderItemPO> poClass() {
        return OrderItemPO.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OrderItemPO toPO(OrderItem domain) {
        final OrderItemPO po = new OrderItemPO();
        po.setProductId(domain.getProductId());
        po.setSkuId(domain.getSkuId());
        po.setProductName(domain.getProductName());
        po.setUnitPrice(domain.getUnitPrice());
        po.setQuantity(domain.getQuantity());
        po.setSubtotal(domain.getSubtotal());
        po.setMainImageUrl(domain.getMainImageUrl());
        po.setSpecSummary(domain.getSpecSummary());
        po.setSpecAttributes(toJson(domain.getSpecAttributes()));
        po.setCustomAttributes(toJson(domain.getCustomAttributes()));
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OrderItem toDomain(OrderItemPO po) {
        return new OrderItem(po.getProductId(), po.getSkuId(), po.getProductName(),
                po.getUnitPrice(), po.getQuantity(), po.getSubtotal(),
                po.getMainImageUrl(), po.getSpecSummary(),
                fromJson(po.getSpecAttributes()), fromJson(po.getCustomAttributes()));
    }

    /**
     * 扩展属性 Map → JSON 字面量（空 Map 序列化为 {@code {}}；领域侧
     * 集合经 freezeAttributes 恒非 null，null 防御性兜底）。
     */
    private static String toJson(Map<String, String> attributes) {
        return attributes == null ? null : JacksonUtil.toJsonString(attributes);
    }

    /**
     * JSON 字面量 → 扩展属性 Map（null/空白 = 无明细，按 null 传入
     * 构造路径——领域 freezeAttributes(null) 恢复为不可变空集合）。
     */
    private static Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return JacksonUtil.fromJsonString(json,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
                });
    }
}