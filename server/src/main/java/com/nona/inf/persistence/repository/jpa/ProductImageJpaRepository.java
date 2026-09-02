package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.catalog.ProductImagePO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

/**
 * 商品图片 JPA 仓储（product_image 表，商品聚合从表行集合，tenant=shopId）。
 * <p>
 * 查询经租户过滤（tenant_id=context）仅命中本店铺商品行——跨店铺访问在
 * 数据访问层即被拦截（fail-closed）；商品维度查询（product_id）与租户
 * 过滤共同定位行集（同一商品下租户列与本店铺一致）。
 *
 * @author nona9961
 */
public interface ProductImageJpaRepository extends ListCrudRepository<ProductImagePO, Long> {

    /**
     * 按归属商品查询全部图片（按 ID 升序，加入序稳定）。
     *
     * @param productId 商品 ID
     * @return 图片列表；无图片返回空列表
     */
    List<ProductImagePO> findByProductIdOrderByIdAsc(Long productId);

    /**
     * 按归属商品删除全部图片（deleteByID 级联删除用）。
     *
     * @param productId 商品 ID
     * @return 删除的行数
     */
    long deleteByProductId(Long productId);
}