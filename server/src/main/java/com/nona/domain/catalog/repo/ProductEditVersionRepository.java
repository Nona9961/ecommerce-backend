package com.nona.domain.catalog.repo;

import com.nona.domain.catalog.entity.ProductEditVersion;
import com.nona.persistence.BaseRepository;

import java.util.List;

/**
 * 商品编辑版本仓储接口（product_edit_version 从表持久化契约，实现在
 * 基础设施层）。
 * <p>
 * append-only 记录行仓储：本接口只提供「追加一行」与「按商品分页查询」，
 * 版本行删除仅经商品级联（删除商品 → 级联删版本行，由 {@code ProductRepository}
 * 的 deleteByID 承载），行级删除语义不存在（接口继承的
 * {@code delete / deleteByID} 实现按无行级删除路径处理）。
 * 历史查询（按商品分页读取）经本仓储按商品分页读取，读写均经租户过滤
 * （tenant=shopId，fail-closed：跨店铺商品版本行不可见）；版本号分配
 * （同商品 MAX+1）与留痕编排（快照序列化 + 触发类型定型）在用例层完成。
 *
 * @author nona9961
 */
public interface ProductEditVersionRepository extends BaseRepository<Long, ProductEditVersion> {

    /**
     * 追加一行版本（append-only 插入：版本号唯一性由 DB 唯一约束
     * (product_id, version_no) 兜底，并发重复插入被拒绝）。
     *
     * @param version 版本记录（工厂创建，待追加）
     * @return 追加后的版本记录（含持久化审计时间）
     */
    ProductEditVersion append(ProductEditVersion version);

    /**
     * 按商品分页列出版本（新版本在前——version_no 倒序）。
     *
     * @param productId 商品 ID（与租户过滤共同定位行集）
     * @param offset    首条偏移量（从 0 开始）
     * @param limit     每页条数
     * @return 版本列表；无版本为空列表
     */
    List<ProductEditVersion> listByProductPaged(Long productId, int offset, int limit);

    /**
     * 按商品统计版本数（分页 total 用）。
     *
     * @param productId 商品 ID
     * @return 版本数
     */
    long countByProduct(Long productId);

    /**
     * 按商品 + 版本号取版本行（回滚素材读取；目标版本不属于当前商品
     * 按不存在呈现——租户过滤 fail-closed，不泄露归属）。
     *
     * @param productId 商品 ID
     * @param versionNo 版本号
     * @return 版本记录；不存在返回 null
     */
    ProductEditVersion getByProductAndVersion(Long productId, int versionNo);

    /**
     * 同商品当前最大版本号（MAX+1 分配的基础读；无版本行返回 0）。
     *
     * @param productId 商品 ID
     * @return 最大版本号；无版本行为 0
     */
    int maxVersionNo(Long productId);
}