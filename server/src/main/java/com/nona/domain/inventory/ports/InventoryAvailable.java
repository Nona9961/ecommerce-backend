package com.nona.domain.inventory.ports;

/**
 * SKU 可售量查询结果（queryAvailable 返回项）：可售 = available 列值；
 * 未初始化/跨店铺 SKU 按可售 0 呈现（zip 语义，调用方无需区分缺失与零）。
 *
 * @param skuId     SKU ID
 * @param available 可售量（非负）
 * @author nona9961
 */
public record InventoryAvailable(Long skuId, int available) {
}