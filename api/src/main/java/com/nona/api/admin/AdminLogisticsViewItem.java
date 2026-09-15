package com.nona.api.admin;

/**
 * 平台物流视图行响应体（平台运营物流监督列表行，约定形状：GET
 * /admin/logistics?shopId=&amp;status=&amp;pageNum=&amp;pageSize=，行单元 =
 * 子单）。
 * <p>
 * 逐字段对齐 {@code domain/logistics/ports/PlatformLogisticsViewItem}
 * record（领域层已冻结形状，本响应体为 api 层呈现：枚举以 name() 字符串
 * 承载、java.time 以 ISO 8601 字符串承载）；timeoutOverdue 为后端行级
 * 判定（PAID 到期 → 标 / 未到期或已发货 → 不标，判定语义收敛在领域
 * 服务实现），前端直接消费不重算。
 *
 * @param subOrderId     子单 ID（行单元主键）
 * @param subOrderNo     子单号（业务单号，运营定位凭据）
 * @param masterOrderId  归属主单 ID（跨店列表区分同买家多店订单）
 * @param shopId         归属店铺 ID
 * @param shopName       店铺名（展示冗余）
 * @param subOrderStatus 子单履约状态（SubOrderStatus 枚举名）
 * @param waybillId      运单 ID（未发货子单为 null）
 * @param company        承运公司（未发货子单为 null）
 * @param trackingNo     运单号（未发货子单为 null）
 * @param waybillStatus  运单物流状态（WaybillStatus 枚举名；未发货子单为 null）
 * @param timeoutAt      发货超时截止时间（ISO 8601；无 deadline 为 null）
 * @param timeoutOverdue 超时未发货标记（后端行级判定）
 * @author nona9961
 */
public record AdminLogisticsViewItem(
        Long subOrderId,
        String subOrderNo,
        Long masterOrderId,
        Long shopId,
        String shopName,
        String subOrderStatus,
        Long waybillId,
        String company,
        String trackingNo,
        String waybillStatus,
        String timeoutAt,
        boolean timeoutOverdue
) {
}