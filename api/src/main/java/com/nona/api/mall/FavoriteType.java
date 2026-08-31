package com.nona.api.mall;

import com.nona.exceptions.BusinessCode;
import com.nona.exceptions.BusinessException;

/**
 * 收藏目标类型（买家收藏：商品 / 店铺）。
 * <p>
 * 契约侧枚举：请求体反序列化按枚举名匹配（非法值由全局异常处理统一
 * 400 generic.validation_failed）；URL 查询参数走 {@link #fromName} 显式解析
 * （非法值同码拒绝，与 body 校验语义一致）。
 *
 * @author nona9961
 */
public enum FavoriteType {

    /**
     * 商品（引用商品 ID；商品实体属目录域，本域仅存引用）
     */
    PRODUCT,

    /**
     * 店铺（引用店铺 ID；店铺实体属目录域，本域仅存引用）
     */
    SHOP;

    /**
     * 按枚举名解析路径/查询参数中的收藏类型。
     *
     * @param name 枚举名（如 PRODUCT / SHOP）；null 或未知值拒绝
     * @return 匹配的收藏类型
     * @throws BusinessException 非法收藏类型（400 generic.validation_failed）
     */
    public static FavoriteType fromName(String name) {
        for (FavoriteType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        throw new BusinessException(BusinessCode.VALIDATION_FAILED.code(), "非法收藏类型", 400);
    }
}