package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.PlatformCategory;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

/**
 * 平台分类工厂：平台分类聚合根的创建入口（ID 生成 + 初始状态定型）。
 * <p>
 * 创建校验：分类名称必填非空；展示排序必须为正数（排序赋值决策在用例层：
 * 请求显式正数则原样采用，否则取当前最大排序 + 1）。
 * 初始状态恒为 ENABLED；名称全局唯一性由用例层守卫 + 数据库唯一约束兜底。
 *
 * @author nona9961
 */
@Component
public class PlatformCategoryFactory {

    /**
     * 创建平台分类：生成 Snowflake ID、初始状态 ENABLED。
     *
     * @param name  分类名称（必填非空）
     * @param order 展示排序（必须为正数）
     * @return 新建平台分类
     */
    public PlatformCategory create(String name, int order) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_CATEGORY_NAME_BLANK.code(), "分类名称不能为空");
        }
        if (order <= 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_CATEGORY_ORDER_INVALID.code(), "分类排序必须为正数");
        }
        return new PlatformCategory(IDUtils.generateID(), name, order, CategoryStatus.ENABLED);
    }
}