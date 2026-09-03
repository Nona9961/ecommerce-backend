package com.nona.domain.catalog.entity;

/**
 * 商品生命周期状态（Product 聚合状态字段，状态机值定型）。
 * <p>
 * 状态机：草稿 → 待审核 → 在售（⇄ 已下架：下架迁移端点属后续阶段，
 * 本期仅值定义完整）；驳回：待审核 → 草稿（可修改重提）；在售商品敏感
 * 字段编辑：在售 → 待审核（字段级分流，旧版生效内容买家继续可见直至
 * 平台裁定）。全部状态迁移守卫收敛在聚合方法（非法迁移拒绝）。
 * 草稿生命周期语义：草稿 = 提交前可编辑、驳回后可修改重提的唯一编辑态
 * （买家不可见）；提交审核后内容冻结（待审期内编辑拒绝）。
 *
 * @author nona9961
 */
public enum ProductStatus {

    /**
     * 草稿：商家编辑中，不对买家生效（可保存不生效；驳回后回到本态可修改重提）。
     */
    DRAFT,

    /**
     * 待审核：提交上架或敏感字段编辑分流后进入，内容冻结，买家继续可见
     * 旧版生效内容直至平台裁定（通过 → 在售 / 驳回 → 草稿）。
     */
    PENDING_REVIEW,

    /**
     * 在售：审核通过，买家可见可购买（严谨在售不变量：主图/类目/品牌齐备）。
     */
    ON_SALE,

    /**
     * 已下架：不在售（下架迁移端点属后续阶段，本期仅定义值与非法的状态
     * 迁移守卫；本状态下无任何合法迁移）。
     */
    DELISTED;

    /**
     * 按枚举名解析查询参数中的商品状态；null 或未知值拒绝。
     *
     * @param name 枚举名（如 PENDING_REVIEW）；null 或未知值拒绝
     * @return 匹配的商品状态
     * @throws com.nona.exceptions.BusinessException 非法状态（400 generic.validation_failed）
     */
    public static ProductStatus fromName(String name) {
        for (ProductStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new com.nona.exceptions.BusinessException(
                com.nona.exceptions.BusinessCode.VALIDATION_FAILED.code(), "非法商品状态", 400);
    }
}