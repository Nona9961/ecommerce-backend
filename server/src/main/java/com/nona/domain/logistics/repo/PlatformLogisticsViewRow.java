package com.nona.domain.logistics.repo;

import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.order.entity.SubOrderStatus;

import java.time.Instant;

/**
 * 平台物流视图仓储行投影（读模型仓储的输出形态：image 表列 → 行值，
 * 不含任何判定/组装语义——超时标记判定由服务实现基于原始列计算）。
 * <p>
 * 行单元 = 子单（镜像 sub_order 行 LEFT JOIN 镜像 waybill 运单行 +
 * 镜像 shop 店铺行）：未发货子单的物流凭据字段（waybillId/company/
 * trackingNo/waybillStatus）为空——LEFT JOIN 未命中即空，是「未发货」
 * 的结构表达而非数据缺失。
 * <p>
 * {@code timeoutAt} 为子单截止时间列原始值（null = 无 deadline 记录），
 * 时间语义为 UTC 墙钟（镜像链路时间列契约）。
 *
 * @param subOrderId     子单 ID
 * @param subOrderNo     子单号
 * @param masterOrderId  归属主单 ID
 * @param shopId         归属店铺 ID
 * @param shopName       店铺名
 * @param subOrderStatus 子单履约状态（列值按枚举名文字映射）
 * @param waybillId      运单 ID（未发货为空）
 * @param company        承运公司（未发货为空）
 * @param trackingNo     运单号（未发货为空）
 * @param waybillStatus  运单物流状态（未发货为空）
 * @param timeoutAt      发货超时截止时间（无 deadline 记录为空）
 */
public record PlatformLogisticsViewRow(Long subOrderId, String subOrderNo,
                                       Long masterOrderId, Long shopId, String shopName,
                                       SubOrderStatus subOrderStatus,
                                       Long waybillId, String company, String trackingNo,
                                       WaybillStatus waybillStatus,
                                       Instant timeoutAt) {
}