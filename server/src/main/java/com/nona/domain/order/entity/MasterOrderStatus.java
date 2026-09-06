package com.nona.domain.order.entity;

import com.nona.exceptions.BusinessCode;
import com.nona.exceptions.BusinessException;

/**
 * 主订单整体状态（MasterOrder 聚合状态字段，派生值定型）。
 * <p>
 * 主单不承载独立履约状态机——整体状态 = 全部子单状态的聚合派生
 * （{@link MasterOrderStatusDeriver} 纯函数：输入子单状态集合 →
 * 输出主单状态），由下单/支付/发货/完成/退款/超时编排在事务内推进
 * 子单后调用派生刷新。
 * <p>
 * 派生语义（钉死，见 {@link MasterOrderStatusDeriver}）：主链
 * 待支付 → 已支付 → 部分发货 → 已发货 → 已完成；旁路状态在
 * 「全部子单一致」时覆盖主链（全部取消 → 已取消；全部关闭 → 已关闭；
 * 全部退款 → 已退款；任一退款中 → 退款中——在途资金显性优先）。
 *
 * @author nona9961
 */
public enum MasterOrderStatus {

    /**
     * 待支付：全部子单待支付。
     */
    PENDING_PAYMENT,

    /**
     * 已支付：全部子单已支付、均未发货（买家视角「待发货」）。
     */
    PAID,

    /**
     * 部分发货：部分子单已发货/已完成、其余未发货。
     */
    PARTIALLY_SHIPPED,

    /**
     * 已发货：全部子单已发货、均未完成。
     */
    SHIPPED,

    /**
     * 已完成：全部子单已完成。
     */
    COMPLETED,

    /**
     * 已取消：全部子单已取消（待支付取消/支付超时，终态）。
     */
    CANCELLED,

    /**
     * 退款中：任一子单退款在途（在途资金显性优先于履约主链，终态前过渡态）。
     */
    REFUNDING,

    /**
     * 已退款：全部子单已退款（主动退款成功，终态）。
     */
    REFUNDED,

    /**
     * 已关闭：全部子单已关闭（发货超时关单，终态）。
     */
    CLOSED;

    /**
     * 按枚举名解析主单状态（查询参数/筛选映射用）；null 或未知值拒绝。
     *
     * @param name 枚举名（如 PARTIALLY_SHIPPED）；null 或未知值拒绝
     * @return 匹配的主单状态
     * @throws BusinessException 非法状态（400 generic.validation_failed）
     */
    public static MasterOrderStatus fromName(String name) {
        for (MasterOrderStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new BusinessException(BusinessCode.VALIDATION_FAILED.code(), "非法主单状态", 400);
    }
}