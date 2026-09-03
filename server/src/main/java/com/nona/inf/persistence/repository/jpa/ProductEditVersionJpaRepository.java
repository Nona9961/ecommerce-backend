package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.catalog.ProductEditVersionPO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 商品编辑版本 JPA 仓储（product_edit_version 表，Product 聚合的
 * append-only 从表行集合，tenant=shopId）。
 * <p>
 * 查询经租户过滤（tenant_id=context）仅命中本店铺商品行——跨店铺版本行
 * 在数据访问层即被拦截（fail-closed）；商品维度查询（product_id）与租户
 * 过滤共同定位行集。版本号唯一性由表级约束兜底
 * （uk_product_edit_version_no，并发重复插入拒绝）；版本行只增不改
 * （本仓储无更新/删除路径，行级删除语义不存在）。
 *
 * @author nona9961
 */
public interface ProductEditVersionJpaRepository extends JpaRepository<ProductEditVersionPO, Long> {

    /**
     * 按归属商品分页查版本（新版本在前——version_no 倒序）。
     *
     * @param productId 商品 ID
     * @param pageable  分页参数
     * @return 版本分页结果
     */
    Page<ProductEditVersionPO> findByProductIdOrderByVersionNoDesc(Long productId, Pageable pageable);

    /**
     * 按归属商品统计版本数（分页 total 用）。
     *
     * @param productId 商品 ID
     * @return 版本数
     */
    long countByProductId(Long productId);

    /**
     * 按商品 + 版本号取版本行（回滚素材读取）。
     *
     * @param productId 商品 ID
     * @param versionNo 版本号
     * @return 版本行；不存在返回空
     */
    Optional<ProductEditVersionPO> findByProductIdAndVersionNo(Long productId, int versionNo);

    /**
     * 同商品当前最大版本号（MAX+1 分配的基础读；无版本行返回 0）。
     *
     * @param productId 商品 ID
     * @return 最大版本号；无版本行为 0
     */
    @Query("select coalesce(max(v.versionNo), 0) from ProductEditVersionPO v where v.productId = :productId")
    Integer maxVersionNo(@Param("productId") Long productId);

    /**
     * 按归属商品删除全部版本行（商品删除级联用，deleteByID 级联路径）。
     *
     * @param productId 商品 ID
     * @return 删除的行数
     */
    long deleteByProductId(Long productId);
}