package com.nona.domain.catalog.repo;

import com.nona.domain.catalog.entity.Product;
import com.nona.persistence.BaseRepository;

import java.util.List;

/**
 * 商品仓储接口（Product 聚合根持久化契约，实现在基础设施层）。
 * <p>
 * 富状态聚合仓储：经脚手架 DifferRepository 变更追踪（主表 product +
 * 从表 product_image / product_attribute 集合），保存由变更集驱动落库；
 * 从表行经租户过滤加载（fail-closed，跨店铺商品行不可见）。删除为
 * 物理删除（级联删从表行 + 主表行，草稿删除语义）。引用存在性查询
 * （类目/品牌）供平台侧禁用守卫跨租户使用（管理员视角全局检查，
 * 调用方需显式读放行）。
 *
 * @author nona9961
 */
public interface ProductRepository extends BaseRepository<Long, Product> {

    /**
     * 按店铺分页列出商品草稿（新商品在前——ID 倒序）。
     *
     * @param shopId 店铺 ID（与租户过滤共同定位行集）
     * @param offset 首条偏移量（从 0 开始）
     * @param limit  每页条数
     * @return 商品列表；无数据为空列表
     */
    List<Product> listByShopPaged(Long shopId, int offset, int limit);

    /**
     * 按店铺统计商品草稿数（分页 total 用）。
     *
     * @param shopId 店铺 ID
     * @return 商品数
     */
    long countByShop(Long shopId);

    /**
     * 是否存在引用指定平台类目的商品（禁用守卫用；跨租户全局检查，
     * 调用方需读放行）。
     *
     * @param categoryId 平台类目 ID
     * @return 存在引用返回 true
     */
    boolean existsByCategoryId(Long categoryId);

    /**
     * 是否存在引用指定品牌的商品（禁用守卫用；跨租户全局检查，
     * 调用方需读放行）。
     *
     * @param brandId 品牌 ID
     * @return 存在引用返回 true
     */
    boolean existsByBrandId(Long brandId);
}