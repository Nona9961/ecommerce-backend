package com.nona.inf.persistence.repository;

import com.nona.domain.catalog.entity.ProductEditVersion;
import com.nona.domain.catalog.repo.ProductEditVersionRepository;
import com.nona.inf.persistence.converters.ProductEditVersionConvertor;
import com.nona.inf.persistence.po.catalog.ProductEditVersionPO;
import com.nona.inf.persistence.repository.jpa.ProductEditVersionJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品编辑版本仓储落地：append-only 记录行仓储（product_edit_version
 * 表，tenant=shopId），插行与按商品分页查询的机械装配（红阶段为装配
 * 底座，领域语义——保存留痕/版本号递增分配/回滚编排——收敛在用例层）。
 * <p>
 * 读：版本行经租户过滤加载（fail-closed，跨店铺商品版本行不可见）；
 * 写：tenant 列由写门禁按请求上下文注入（商家请求 tenant=当前店铺）。
 * 版本行只增不改：行级删除语义不存在（versionNo 唯一性由表级约束兜底
 * 并发冲突），级联删除仅经商品仓储 deleteByID（删商品 → 删版本行）。
 *
 * @author nona9961
 */
@Component
public class ProductEditVersionRepositoryImpl implements ProductEditVersionRepository {

    /**
     * 版本表 JPA 仓储
     */
    private final ProductEditVersionJpaRepository jpaRepository;

    /**
     * 版本行转换器
     */
    private final ProductEditVersionConvertor convertor;

    /**
     * 构造版本仓储。
     *
     * @param jpaRepository 版本表 JPA 仓储
     * @param convertor     版本行转换器
     */
    public ProductEditVersionRepositoryImpl(ProductEditVersionJpaRepository jpaRepository,
                                            ProductEditVersionConvertor convertor) {
        this.jpaRepository = jpaRepository;
        this.convertor = convertor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProductEditVersion append(ProductEditVersion version) {
        return convertor.toDomain(jpaRepository.save(convertor.toPO(version)));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 列表行未登记变更追踪（只读呈现，append-only 行无更新路径）。分页
     * 参数与商品列表同形态（offset/limit → 页码换算）。
     */
    @Override
    public List<ProductEditVersion> listByProductPaged(Long productId, int offset, int limit) {
        final Page<ProductEditVersionPO> page = jpaRepository.findByProductIdOrderByVersionNoDesc(
                productId, PageRequest.of(offset / limit, limit));
        return page.stream().map(convertor::toDomain).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countByProduct(Long productId) {
        return jpaRepository.countByProductId(productId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProductEditVersion getByProductAndVersion(Long productId, int versionNo) {
        return jpaRepository.findByProductIdAndVersionNo(productId, versionNo)
                .map(convertor::toDomain)
                .orElse(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int maxVersionNo(Long productId) {
        return jpaRepository.maxVersionNo(productId);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 按行 ID 取版本行（行级读取，回滚/查询路径经商品维度接口，本方法
     * 供基类契约完备）。
     */
    @Override
    public ProductEditVersion getByID(Long id) {
        return jpaRepository.findById(id).map(convertor::toDomain).orElse(null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 追加插入（append-only：save 即插一行，无更新路径）。
     */
    @Override
    public boolean save(ProductEditVersion version) {
        jpaRepository.save(convertor.toPO(version));
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 本仓储无行级删除语义（append-only 版本链）：删除仅经商品仓储
     * deleteByID 级联（删商品 → 删版本行）。按行删除一律拒绝。
     */
    @Override
    public int delete(ProductEditVersion version) {
        throw new UnsupportedOperationException("版本行禁止行级删除（append-only，删除经商品级联）");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 行级删除语义不存在（见 {@link #delete(ProductEditVersion)}）。
     */
    @Override
    public int deleteByID(Long id) {
        throw new UnsupportedOperationException("版本行禁止行级删除（append-only，删除经商品级联）");
    }
}