package com.nona.domain.catalog.repo;

/**
 * SKU 商品摘要视图（catalog 跨上下文只读投影行，库存列表页 catalog
 * join 用）。
 * <p>
 * 语义约束：
 * <ul>
 *     <li>行单元 = SKU 维度（product_sku 主表行视角 + 商品主体摘要
 *         JOIN）；库存聚合仅持 skuId 引用，商品名/规格摘要等展示字段
 *         经本投影跨上下文装配——只读，禁止经本视图写路径；</li>
 *     <li>店铺语义：读经租户过滤（fail-closed——跨店铺 SKU 按不存在
 *         呈现，归属不泄露）；</li>
 *     <li>specSummary 为规格组合可读摘要（配置序拼接；无规格 SKU 可空）——
 *         与 SKU 实体展示语义一致；productId 为规格归属商品 ID（商品
 *         展示锚点）。</li>
 * </ul>
 *
 * @param skuId       SKU ID（跨域引用键）
 * @param productId   归属商品 ID
 * @param productName 商品名称
 * @param specSummary 规格组合可读摘要（无规格 SKU 为 null）
 * @author nona9961
 */
public record SkuProductView(
        Long skuId,
        Long productId,
        String productName,
        String specSummary
) {
}