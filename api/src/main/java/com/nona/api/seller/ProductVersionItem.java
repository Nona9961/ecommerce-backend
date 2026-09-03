package com.nona.api.seller;

/**
 * 商品编辑版本列表项响应体（商家端版本历史查询的返回形态）。
 * <p>
 * 版本历史按商品分页（新版本在前）；条目含版本号、触发类型、操作人与
 * 产生时间，以及由快照派生的内容摘要（商品名称 + 图片/属性/SKU 计数，
 * 轻量呈现——列表不承载完整快照）。触发类型本期仅 EDIT / ROLLBACK
 * 产生（REVIEW_PASS / REJECT 为审核流预留枚举值，属后续阶段）。
 *
 * @param versionNo   版本号（同商品内单调递增，新版本在前排序键）
 * @param triggerType 触发类型（EDIT / ROLLBACK；REVIEW_PASS / REJECT 属后续阶段）
 * @param operator    操作人（认证上下文身份标识）
 * @param createdAt   版本产生时间（ISO 8601）
 * @param summary     内容摘要（商品名称 + 图片/属性/SKU 计数，派生自快照）
 *
 * @author nona9961
 */
public record ProductVersionItem(
        int versionNo,
        String triggerType,
        String operator,
        String createdAt,
        String summary
) {
}