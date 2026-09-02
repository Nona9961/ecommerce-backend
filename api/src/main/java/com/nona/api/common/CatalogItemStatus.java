package com.nona.api.common;

import com.nona.exceptions.BusinessCode;
import com.nona.exceptions.BusinessException;

/**
 * 平台货架目录项状态（平台分类/品牌共享的契约枚举，列表状态过滤用）。
 * <p>
 * 与 catalog 域 {@code CategoryStatus} / {@code BrandStatus} 语义一一对应
 * （enabled 启用 / disabled 禁用），映射点收敛在各端用例（switch 显式转换）。
 * 禁用 = 软删（disable-not-delete）：禁用后既有商品保持历史挂载可见，
 * 新商品不可挂载（商品侧挂载守卫属商品域后续工作）。
 *
 * @author nona9961
 */
public enum CatalogItemStatus {

    /**
     * 启用：可被新商品挂载。
     */
    ENABLED,

    /**
     * 禁用：既有挂载保持，新挂载拒绝（语义由商品域消费）。
     */
    DISABLED;

    /**
     * 按枚举名解析查询参数中的状态；null 或未知值拒绝。
     *
     * @param name 枚举名（如 ENABLED）；null 或未知值拒绝
     * @return 匹配的状态
     * @throws BusinessException 非法状态（400 generic.validation_failed）
     */
    public static CatalogItemStatus fromName(String name) {
        for (final CatalogItemStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new BusinessException(BusinessCode.VALIDATION_FAILED.code(), "非法目录项状态", 400);
    }
}