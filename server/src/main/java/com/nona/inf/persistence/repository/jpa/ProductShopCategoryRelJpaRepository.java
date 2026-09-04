package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.catalog.ProductShopCategoryRelPO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 商品-店铺分类绑定从表 JPA 仓储（product_shop_category_rel）。
 *
 * @author nona9961
 */
public interface ProductShopCategoryRelJpaRepository extends JpaRepository<ProductShopCategoryRelPO, Long> {

    /**
     * 按商品 ID 装载绑定行（保持 ID 升序≈绑定序，从表装配输入）。
     *
     * @param productId 商品 ID
     * @return 绑定行列表；无绑定为空列表
     */
    List<ProductShopCategoryRelPO> findByProductIdOrderByIdAsc(Long productId);

    /**
     * 按商品 ID 级联删除绑定行（删除商品时清理从表）。
     *
     * @param productId 商品 ID
     * @return 删除行数
     */
    long deleteByProductId(Long productId);

    /**
     * 是否存在绑定指定店铺分类的商品（分类删除引用守卫；租户过滤内
     * 同店查询）。
     *
     * @param shopCategoryId 店铺分类 ID
     * @return 存在绑定返回 true
     */
    boolean existsByShopCategoryId(Long shopCategoryId);

    /**
     * 按店铺分类分页取绑定商品 ID（按商品 ID 升序——创建序，先创建的
     * 先展示；租户过滤内同店）。
     *
     * @param shopCategoryId 店铺分类 ID
     * @param pageable       分页请求
     * @return 商品 ID 分页
     */
    @Query("select r.productId from ProductShopCategoryRelPO r where r.shopCategoryId = :shopCategoryId order by r.productId")
    Page<Long> findProductIdPageByShopCategoryId(@Param("shopCategoryId") Long shopCategoryId,
                                                 Pageable pageable);

    /**
     * 按店铺分类统计绑定商品数（分页 total 用；租户过滤内同店）。
     *
     * @param shopCategoryId 店铺分类 ID
     * @return 商品数
     */
    long countByShopCategoryId(Long shopCategoryId);
}