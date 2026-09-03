package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.catalog.SkuPO;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 商品 SKU JPA 仓储（product_sku 表，商品聚合从表行集合，tenant=shopId）。
 * <p>
 * 查询经租户过滤（tenant_id=context）仅命中本店铺商品行——跨店铺访问在
 * 数据访问层即被拦截（fail-closed）；商品维度查询（product_id）与租户
 * 过滤共同定位行集（同一商品下租户列与本店铺一致）。spec_hash 唯一性
 * 由表级约束兜底（uk_product_sku_spec_hash，并发重复落库拒绝）。
 *
 * @author nona9961
 */
public interface SkuJpaRepository extends JpaRepository<SkuPO, Long> {

    /**
     * 按归属商品查询全部 SKU（按 ID 升序，与模板展开序一致——模板展开
     * 时 SKU 按组合序连续生成，ID 升序即展开序）。
     *
     * @param productId 商品 ID
     * @return SKU 列表；无 SKU 返回空列表
     */
    List<SkuPO> findByProductIdOrderByIdAsc(Long productId);

    /**
     * 按归属商品删除全部 SKU（deleteByID 级联删除用）。
     *
     * @param productId 商品 ID
     * @return 删除的行数
     */
    long deleteByProductId(Long productId);
}