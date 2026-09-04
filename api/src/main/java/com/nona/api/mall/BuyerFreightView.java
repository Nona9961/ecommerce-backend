package com.nona.api.mall;

/**
 * 买家商品详情-运费模板规则概要（B6.1 运费说明展示；商品未绑模板时
 * freight=null，运费区按无模板呈现——运费试算在结算页由订单域完成）。
 *
 * @param templateId    模板 ID
 * @param name          模板名称
 * @param ruleType      规则类型（FREE 包邮 / PER_ITEM 按件 / THRESHOLD_FREE 满额免邮）
 * @param perItemPrice  按件单价（分；仅 PER_ITEM 非 null）
 * @param baseFreight   基础运费（分；仅 THRESHOLD_FREE 非 null）
 * @param freeThreshold 免邮阈值（分；仅 THRESHOLD_FREE 非 null）
 * @author nona9961
 */
public record BuyerFreightView(Long templateId, String name, String ruleType,
                               Long perItemPrice, Long baseFreight, Long freeThreshold) {
}
