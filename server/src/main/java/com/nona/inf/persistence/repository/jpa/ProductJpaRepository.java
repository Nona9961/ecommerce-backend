package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.catalog.ProductPO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.ListCrudRepository;

/**
 * 商品 JPA 仓储（product 表，商品聚合根根行，tenant=shopId）。
 * <p>
 * 查询经租户过滤（tenant_id=context）仅命中本店铺商品——跨店铺访问在
 * 数据访问层即被拦截（fail-closed）；店铺维度查询（shop_id）与租户
 * 过滤共同定位行集（同一店铺下租户列与 shop_id 一致）。引用存在性查询
 * （category_id / brand_id）供平台侧禁用守卫使用——跨租户全局语义，
 * 调用方需显式读放行（@CrossTenant）。
 *
 * @author nona9961
 */
public interface ProductJpaRepository extends ListCrudRepository<ProductPO, Long> {

    /**
     * 按店铺分页查询商品（新商品在前——ID 倒序）。
     *
     * @param shopId   店铺 ID
     * @param pageable 分页参数（页码/条数/排序）
     * @return 分页结果（total 供统计复用）
     */
    Page<ProductPO> findByShopIdOrderByIdDesc(Long shopId, Pageable pageable);

    /**
     * 按店铺统计商品数（分页 total 用）。
     *
     * @param shopId 店铺 ID
     * @return 商品数
     */
    long countByShopId(Long shopId);

    /**
     * 是否存在引用指定平台类目的商品（禁用守卫用）。
     *
     * @param categoryId 平台类目 ID
     * @return 存在引用返回 true
     */
    boolean existsByCategoryId(Long categoryId);

    /**
     * 是否存在引用指定品牌的商品（禁用守卫用）。
     *
     * @param brandId 品牌 ID
     * @return 存在引用返回 true
     */
    boolean existsByBrandId(Long brandId);
}