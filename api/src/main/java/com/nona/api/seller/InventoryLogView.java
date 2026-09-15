package com.nona.api.seller;

/**
 * 库存流水行响应体（商家库存流水查看分页行，约定形状：GET
 * /seller/inventory/{skuId}/logs?pageNum=&amp;pageSize=，对齐 InventoryLog
 * 实体：append-only 不可变行，前后三态快照全量呈现——任意一行可复现
 * 该 SKU 该时点库存全貌）。
 *
 * @param id             流水行 ID
 * @param skuId          归属 SKU ID
 * @param type           流水类型（InventoryLogType 枚举名，5 型全量）
 * @param delta          变动数量（非零；订单驱动型恒正，手动调整型带符号）
 * @param orderId        订单 ID（订单驱动型非空；手动调整型恒空）
 * @param beforeAvailable 变动前可售量
 * @param beforeHeld     变动前预占量
 * @param beforeSold     变动前已售量
 * @param afterAvailable 变动后可售量
 * @param afterHeld      变动后预占量
 * @param afterSold      变动后已售量
 * @param operator       操作人（手动调整型必填；订单驱动型可空）
 * @param reason         调整原因（可空——原因可选不强制）
 * @param createdAt      行产生时间（ISO 8601）
 * @author nona9961
 */
public record InventoryLogView(
        Long id,
        Long skuId,
        String type,
        int delta,
        Long orderId,
        int beforeAvailable,
        int beforeHeld,
        int beforeSold,
        int afterAvailable,
        int afterHeld,
        int afterSold,
        String operator,
        String reason,
        String createdAt
) {
}