package com.nona.api.seller;

/**
 * 运费模板详情响应体（商家端模板查询/创建/更新/启停的返回形态）。
 * <p>
 * 金额单位均为分；按规则类型解释计费参数：FREE 三者皆空、PER_ITEM
 * 仅 perItemPrice、THRESHOLD_FREE 仅 baseFreight 与 freeThreshold
 * （多余参数不呈现，null 序列化省略）。
 *
 * @param id             模板 ID
 * @param shopId         归属店铺 ID（当前店铺）
 * @param name           模板名称
 * @param ruleType       运费规则（FREE / PER_ITEM / THRESHOLD_FREE）
 * @param perItemPrice   按件单价（分；仅 PER_ITEM）
 * @param baseFreight    基础运费（分；仅 THRESHOLD_FREE）
 * @param freeThreshold  免邮阈值（分；仅 THRESHOLD_FREE）
 * @param status         模板状态（ENABLED 启用 / DISABLED 停用）
 * @author nona9961
 */
public record FreightTemplateDetail(
        Long id,
        Long shopId,
        String name,
        String ruleType,
        Long perItemPrice,
        Long baseFreight,
        Long freeThreshold,
        String status
) {
}