package com.nona.api.common;

/**
 * 商品生命周期状态（商家端提交/查看与平台端审核列表共享的契约枚举）。
 * <p>
 * 与 catalog 域 {@code ProductStatus} 一一对应，映射点收敛在各端用例
 * （enum → enum 显式 switch），契约演进不侵入领域。
 *
 * @author nona9961
 */
public enum ProductLifecycleStatus {

    /**
     * 草稿：商家编辑中，不对买家生效（提交前的编辑态；驳回后的修改重提态）。
     */
    DRAFT,

    /**
     * 待审核：提交审核或敏感字段编辑分流后进入，内容冻结，买家继续可见
     * 旧版生效内容直至平台裁定。
     */
    PENDING_REVIEW,

    /**
     * 在售：审核通过，买家可见可购买。
     */
    ON_SALE,

    /**
     * 已下架：不在售（下架迁移端点属后续阶段，本期仅状态值定义完整）。
     */
    DELISTED;

    /**
     * 按枚举名解析查询参数中的商品状态；null 或未知值拒绝。
     *
     * @param name 枚举名（如 PENDING_REVIEW）；null 或未知值拒绝
     * @return 匹配的商品状态
     * @throws com.nona.exceptions.BusinessException 非法状态（400 generic.validation_failed）
     */
    public static ProductLifecycleStatus fromName(String name) {
        for (ProductLifecycleStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new com.nona.exceptions.BusinessException(
                com.nona.exceptions.BusinessCode.VALIDATION_FAILED.code(), "非法商品状态", 400);
    }
}