package com.nona.api.seller;

/**
 * 商品运费模板绑定请求（S9.2 商品绑模板——编辑页运费模板下拉选择保存
 * 形态）：模板必须存在且属于商品所属店铺（跨店铺模板按不存在呈现 404，
 * 不泄露归属）；停用模板允许绑定（展示历史归属合法，新订单计费守卫由
 * 运费计算器承载）。null=解绑（商品无运费模板，详情运费区按无模板呈现）。
 *
 * @param freightTemplateId 目标运费模板 ID（null=解绑）
 * @author nona9961
 */
public record FreightTemplateBindRequest(Long freightTemplateId) {
}
