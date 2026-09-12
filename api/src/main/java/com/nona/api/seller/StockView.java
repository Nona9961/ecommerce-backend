package com.nona.api.seller;

/**
 * 库存三态展示行响应体（SKU 维度；商家库存列表页行与手工调整响应共用，
 * WU-47 约定形状：GET /seller/inventory?pageNum=&amp;pageSize= 与
 * PUT /seller/inventory/{skuId}）。
 * <p>
 * 形态契约：库存数量为整数件，无金额换算；productId/productName/
 * specSummary 为 catalog 域 join 展示字段（库存聚合仅持 skuId 引用，
 * join 由商家查询编排承载——本店全集，join 缺失即数据异常呈现）；
 * 三态恒非负（available 可售 / held 预占 / sold 已售）。
 *
 * @param skuId       SKU ID（跨域引用键，库存行业务唯一键）
 * @param productId   归属商品 ID（catalog 域 join，展示用）
 * @param productName 商品名称（catalog 域 join，展示用）
 * @param specSummary SKU 规格组合可读摘要（无规格 SKU 可空）
 * @param available   可售量（调整操作的唯一作用态）
 * @param held        预占量（只读）
 * @param sold        已售量（只读）
 * @author nona9961
 */
public record StockView(
        Long skuId,
        Long productId,
        String productName,
        String specSummary,
        int available,
        int held,
        int sold
) {
}