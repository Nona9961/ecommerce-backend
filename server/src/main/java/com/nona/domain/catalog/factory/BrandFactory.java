package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.Brand;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

/**
 * 品牌工厂：品牌聚合根的创建入口（ID 生成 + 初始状态定型）。
 * <p>
 * 创建校验：品牌名称必填非空（logo 可空）；名称全局唯一性
 * 由用例层守卫 + 数据库唯一约束兜底。初始状态恒为 ENABLED。
 *
 * @author nona9961
 */
@Component
public class BrandFactory {

    /**
     * 创建品牌：生成 Snowflake ID、初始状态 ENABLED。
     *
     * @param name 品牌名称（必填非空）
     * @param logo 品牌 logo（可空）
     * @return 新建品牌
     */
    public Brand create(String name, String logo) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_BRAND_NAME_BLANK.code(), "品牌名称不能为空");
        }
        return new Brand(IDUtils.generateID(), name, logo, BrandStatus.ENABLED);
    }
}