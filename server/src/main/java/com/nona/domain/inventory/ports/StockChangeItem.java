package com.nona.domain.inventory.ports;

/**
 * 订单驱动库存变动的单 SKU 明细项（预占/确认/回滚共用）。
 *
 * @param skuId    SKU ID
 * @param quantity 数量（必须为正）
 * @author nona9961
 */
public record StockChangeItem(Long skuId, int quantity) {
}