package com.nona.api.seller;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 店铺详情响应体（商家端店铺查询与信息编辑的返回形态）。
 *
 * @param id          店铺 ID
 * @param name        店铺名称
 * @param logo        店铺 logo（可空）
 * @param description 店铺简介（可空）
 * @param status      店铺状态（NORMAL 正常 / FROZEN 冻结）
 * @param categories  店铺分类列表（按展示排序升序；无分类为空列表）
 */
public record ShopDetail(
        Long id,
        String name,
        String logo,
        String description,
        String status,
        List<ShopCategoryItem> categories
) {

    /**
     * 紧凑构造器：防御 null，无分类以空列表呈现。
     *
     * @param id          店铺 ID
     * @param name        店铺名称
     * @param logo        店铺 logo
     * @param description 店铺简介
     * @param status      店铺状态
     * @param categories  店铺分类列表
     */
    public ShopDetail {
        categories = Objects.requireNonNullElse(categories, Collections.emptyList());
    }
}