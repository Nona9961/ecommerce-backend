package com.nona.api.seller;

/**
 * 商品 SKU 条目响应体（规格模板配置结果与 SKU 维护响应共用）。
 * <p>
 * specHash 为规范化摘要（SHA-256 hex 定长 64，与维度配置顺序无关，
 * 同商品内唯一）；specSummary 为可读摘要（配置序拼接，展示用）；
 * price 为销售价（分，null=未定价——草稿期合法形态）；enabled 默认
 * 停用（fail-closed：商家显式启用，停用 SKU 不出售）。
 *
 * @param id          SKU ID（跨域引用键）
 * @param specHash    规格组合规范化摘要
 * @param specSummary 规格组合可读摘要
 * @param price       销售价（分；null=未定价）
 * @param enabled     是否启用
 */
public record SkuItem(
        Long id,
        String specHash,
        String specSummary,
        Long price,
        boolean enabled
) {
}