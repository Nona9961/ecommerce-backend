package com.nona.api.mall;

import com.nona.exceptions.BusinessCode;
import com.nona.exceptions.BusinessException;

/**
 * 买家订单状态（线上契约枚举，接口层冻结，形状钉死前端
 * mall-trading.types.ts {@code MallOrderStatus} 9 值逐名对应）。
 * <p>
 * 值域 = 主单状态派生枚举（MasterOrderStatus 9 值）与子单履约状态
 * （SubOrderStatus 8 值，缺 PARTIALLY_SHIPPED——主单派生态）的并集，
 * 交易面视图（OrderView / SubOrderView.status）统一承载；枚举名即线上
 * JSON 值（Jackson 同名序列化，前端类型对齐）。
 * <p>
 * 查询语义（GET /mall/orders 状态 tab——前端 ORDER_TABS：
 * 全部 + 六态，REFUNDING / PARTIALLY_SHIPPED / CLOSED 归「全部」不
 * 传参）：本枚举仅承载「tab 值」语义，tab → 仓储过滤枚举集合的映射
 * 收敛在消费编排层（BuyerOrderQuery，冻结 javadoc 内固化映射表）。
 *
 * @author nona9961
 */
public enum MallOrderStatus {

    /**
     * 待支付（全部子单待支付）。
     */
    PENDING_PAYMENT,

    /**
     * 已支付（全部子单已支付、均未发货；买家视角「待发货」）。
     */
    PAID,

    /**
     * 部分发货（部分子单已发货/已完成、其余未发货；主单派生态——仅
     * 订单视图呈现，不进状态 tab 筛选）。
     */
    PARTIALLY_SHIPPED,

    /**
     * 已发货（全部子单已发货、均未完成）。
     */
    SHIPPED,

    /**
     * 已完成（全部子单已完成）。
     */
    COMPLETED,

    /**
     * 已取消（全部子单已取消，终态）。
     */
    CANCELLED,

    /**
     * 退款中（任一子单退款在途；订单视图呈现，不进状态 tab 筛选）。
     */
    REFUNDING,

    /**
     * 已退款（全部子单已退款，终态）。
     */
    REFUNDED,

    /**
     * 已关闭（全部子单已关闭，发货超时关单终态；不进状态 tab 筛选）。
     */
    CLOSED;

    /**
     * 按枚举名解析买家订单状态（查询参数 / 筛选映射用）；null 或未知
     * 值拒绝（非法 tab 参数即 400，fail-closed——前端 tab 常量与后端
     * 值域同源，未知名 = 契约漂移早暴露）。
     *
     * @param name 枚举名（如 PARTIALLY_SHIPPED）；null 或未知值拒绝
     * @return 匹配的状态枚举
     * @throws BusinessException 非法状态（400 generic.validation_failed）
     */
    public static MallOrderStatus fromName(String name) {
        for (MallOrderStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new BusinessException(BusinessCode.VALIDATION_FAILED.code(), "非法买家订单状态", 400);
    }
}