package com.nona.api.seller;

import jakarta.validation.constraints.NotBlank;

/**
 * 运费模板规则请求体（商家端）：创建与更新共用，名称/规则/参数整体替换。
 * <p>
 * ruleType 取值 FREE（包邮）/ PER_ITEM（按件）/ THRESHOLD_FREE（满额免邮）：
 * PER_ITEM 需携带 perItemPrice（按件单价）；THRESHOLD_FREE 需携带
 * baseFreight（未达额基础运费）与 freeThreshold（免邮阈值）；
 * FREE 不携带计费参数。金额单位均为分，参数与规则不匹配由领域校验拒绝。
 *
 * @param name           模板名称（必填）
 * @param ruleType       运费规则（必填：FREE / PER_ITEM / THRESHOLD_FREE）
 * @param perItemPrice   按件单价（分，PER_ITEM 必填）
 * @param baseFreight    基础运费（分，THRESHOLD_FREE 必填）
 * @param freeThreshold  免邮阈值（分，THRESHOLD_FREE 必填）
 * @author nona9961
 */
public record FreightTemplateRequest(
        @NotBlank(message = "模板名称不能为空") String name,
        @NotBlank(message = "运费规则不能为空") String ruleType,
        Long perItemPrice,
        Long baseFreight,
        Long freeThreshold
) {
}