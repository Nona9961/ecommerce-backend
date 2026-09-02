package com.nona.inf.persistence.repository;

import com.nona.domain.catalog.entity.Brand;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.domain.catalog.repo.BrandRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.persistence.converters.BrandConvertor;
import com.nona.inf.persistence.repository.jpa.BrandJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 品牌仓储落地：直接委托 JPA 仓储操作单行 brand 表，
 * 实现域仓储契约 {@link BrandRepository}。
 * <p>
 * 简单单表聚合（无集合子实体），不经 DifferRepository 变更追踪——快照登记
 * 无收益，与收藏条目仓储的简单聚合实现同构。名称唯一性双层保障：
 * 用例层守卫（findByName 前置查重）+ 数据库唯一约束兜底——并发竞态下
 * 插入/改名撞唯一约束时捕获数据完整性冲突并翻译为业务冲突
 * （{@code catalog.brand_name_conflict}，400），不做静默吞错。
 * delete/deleteByID 为物理删行（脚手架契约，返回真实影响行数 0/1）；
 * 域内「删除」语义 = 禁用软删（disable），域流程不调用物理删除。
 *
 * @author nona9961
 */
@Component
public class BrandRepositoryImpl implements BrandRepository {

    /**
     * 品牌 JPA 仓储
     */
    private final BrandJpaRepository jpaRepository;

    /**
     * 品牌 ↔ PO 转换器
     */
    private final BrandConvertor convertor;

    /**
     * 构造仓储实现。
     *
     * @param jpaRepository 品牌 JPA 仓储
     * @param convertor     品牌 ↔ PO 转换器
     */
    public BrandRepositoryImpl(BrandJpaRepository jpaRepository, BrandConvertor convertor) {
        this.jpaRepository = jpaRepository;
        this.convertor = convertor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Brand getByID(Long id) {
        return jpaRepository.findById(id)
                .map(convertor::toDomain)
                .orElse(null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 插入/更新前立即 flush，使唯一约束冲突在调用栈内抛出并翻译为业务冲突
     * （延迟到事务提交才 flush 时，冲突将越过本捕获点污染用例层事务）。
     */
    @Override
    public boolean save(Brand brand) {
        try {
            jpaRepository.saveAndFlush(convertor.toPO(brand));
            return true;
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_BRAND_NAME_CONFLICT.code(), "品牌名称已存在");
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 物理删行（脚手架契约路径，域流程不调用——「删除」= 禁用软删）。
     */
    @Override
    public int delete(Brand brand) {
        return brand == null ? 0 : deleteByID(brand.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 物理删行并返回真实删除条数（0/1）。
     */
    @Override
    public int deleteByID(Long id) {
        if (id == null || !jpaRepository.existsById(id)) {
            return 0;
        }
        jpaRepository.deleteById(id);
        return 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Brand> findByName(String name) {
        return jpaRepository.findByName(name).map(convertor::toDomain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Brand> listAll() {
        return jpaRepository.findAllByOrderByIdAsc().stream()
                .map(convertor::toDomain)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Brand> listByStatus(BrandStatus status) {
        return jpaRepository.findByStatusOrderByIdAsc(status).stream()
                .map(convertor::toDomain)
                .toList();
    }
}