package com.nona.inf.persistence.repository;

import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.domain.catalog.entity.PlatformCategory;
import com.nona.domain.catalog.repo.PlatformCategoryRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.persistence.converters.PlatformCategoryConvertor;
import com.nona.inf.persistence.repository.jpa.PlatformCategoryJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 平台分类仓储落地：直接委托 JPA 仓储操作单行 platform_category 表，
 * 实现域仓储契约 {@link PlatformCategoryRepository}。
 * <p>
 * 简单单表聚合（无集合子实体），不经 DifferRepository 变更追踪——快照登记
 * 无收益，与收藏条目仓储的简单聚合实现同构。名称唯一性双层保障：
 * 用例层守卫（findByName 前置查重）+ 数据库唯一约束兜底——并发竞态下
 * 插入/改名撞唯一约束时捕获数据完整性冲突并翻译为业务冲突
 * （{@code catalog.category_name_conflict}，400），不做静默吞错。
 * delete/deleteByID 为物理删行（脚手架契约，返回真实影响行数 0/1）；
 * 域内「删除」语义 = 禁用软删（disable），域流程不调用物理删除。
 *
 * @author nona9961
 */
@Component
public class PlatformCategoryRepositoryImpl implements PlatformCategoryRepository {

    /**
     * 平台分类 JPA 仓储
     */
    private final PlatformCategoryJpaRepository jpaRepository;

    /**
     * 平台分类 ↔ PO 转换器
     */
    private final PlatformCategoryConvertor convertor;

    /**
     * 构造仓储实现。
     *
     * @param jpaRepository 平台分类 JPA 仓储
     * @param convertor     平台分类 ↔ PO 转换器
     */
    public PlatformCategoryRepositoryImpl(PlatformCategoryJpaRepository jpaRepository,
                                          PlatformCategoryConvertor convertor) {
        this.jpaRepository = jpaRepository;
        this.convertor = convertor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PlatformCategory getByID(Long id) {
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
    public boolean save(PlatformCategory category) {
        try {
            jpaRepository.saveAndFlush(convertor.toPO(category));
            return true;
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_CATEGORY_NAME_CONFLICT.code(), "分类名称已存在");
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 物理删行（脚手架契约路径，域流程不调用——「删除」= 禁用软删）。
     */
    @Override
    public int delete(PlatformCategory category) {
        return category == null ? 0 : deleteByID(category.getId());
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
    public Optional<PlatformCategory> findByName(String name) {
        return jpaRepository.findByName(name).map(convertor::toDomain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<PlatformCategory> listAll() {
        return jpaRepository.findAllByOrderByOrderNoAscIdAsc().stream()
                .map(convertor::toDomain)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<PlatformCategory> listByStatus(CategoryStatus status) {
        return jpaRepository.findByStatusOrderByOrderNoAscIdAsc(status).stream()
                .map(convertor::toDomain)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int findMaxOrder() {
        return jpaRepository.findMaxOrderNo();
    }
}