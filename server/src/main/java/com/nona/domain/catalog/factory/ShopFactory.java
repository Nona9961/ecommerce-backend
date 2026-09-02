package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.entity.ShopCategory;
import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

/**
 * 店铺工厂：店铺聚合根的创建入口（ID 生成 + 初始状态定型）。
 * <p>
 * 创建校验：店铺名称必填非空（logo/简介可空）；分类创建要求归属店铺非空、
 * 分类名称非空。店铺初始状态恒为 NORMAL（冻结状态由平台侧后续阶段设置）；
 * 店铺分类创建的 order 由聚合新增路径分配（本工厂不预设排序值）。
 *
 * @author nona9961
 */
@Component
public class ShopFactory {

    /**
     * 创建店铺：生成 Snowflake ID、初始状态 NORMAL。
     *
     * @param name        店铺名称（必填非空）
     * @param logo        店铺 logo（可空）
     * @param description 店铺简介（可空）
     * @return 新建店铺（初始无分类）
     */
    public Shop createShop(String name, String logo, String description) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_NAME_BLANK.code(), "店铺名称不能为空");
        }
        return new Shop(IDUtils.generateID(), name, logo, description, ShopStatus.NORMAL);
    }

    /**
     * 创建店铺分类：生成独立 Snowflake ID 并绑定归属店铺（order 由聚合分配）。
     *
     * @param shop 归属店铺（必填非空）
     * @param name 分类名称（必填非空）
     * @return 新建分类（待加入店铺聚合）
     */
    public ShopCategory createCategory(Shop shop, String name) {
        if (shop == null) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_REQUIRED.code(), "店铺不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_CATEGORY_NAME_BLANK.code(), "分类名称不能为空");
        }
        return new ShopCategory(IDUtils.generateID(), shop.getId(), name);
    }
}