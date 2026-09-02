package com.nona.domain.catalog.entity;

/**
 * 运费模板状态：启用 / 停用。
 * <p>
 * 停用模板禁止用于新订单计费（运费计算器领域守卫拒绝）；
 * 历史订单不受影响（订单金额与运费在订单域快照固化，与模板解耦）。
 *
 * @author nona9961
 */
public enum FreightTemplateStatus {

    /**
     * 启用：可参与新订单运费计算。
     */
    ENABLED,

    /**
     * 停用：新订单不可用；已生成订单不受影响。
     */
    DISABLED
}