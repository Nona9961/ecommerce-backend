package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.domain.identity.entity.AccountShopRel;
import com.nona.domain.identity.factory.AccountFactory;
import com.nona.domain.identity.repo.AccountShopRelRepository;
import com.nona.inf.persistence.converters.RdbGeneralConvertor;
import com.nona.inf.persistence.po.identity.AccountShopRelPO;
import com.nona.inf.persistence.repository.jpa.AccountShopRelJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 账号-店铺关联仓储落地：{@link DifferRepository} 包装单表 account_shop_rel，
 * 实现域仓储契约 {@link AccountShopRelRepository}（Account 聚合内关联实体，
 * 单行实体无子对象；入驻审核通过时写入，登录链路经 findByAccountId 读取）。
 * <p>
 * 变更追踪：读 → track 快照 → 保存 （新增 doInsert / 属性变更 doUpdate）
 * 由 changeTracking 框架计算变更集驱动，属性级 diff 落库。
 * 绑定创建（{@link #bind}）参照收藏等聚合内关联实体的幂等语义直连 JPA 仓储：
 * 查重 + saveAndFlush 立即落库，并发重复撞唯一约束时捕获冲突视为已存在
 * （延迟 flush 会使冲突越过仓储捕获点，幂等兜底失效）。
 *
 * @author nona9961
 */
@Component
public class AccountShopRelRepositoryImpl
        extends DifferRepository<AccountShopRel, AccountShopRelPO, Void>
        implements AccountShopRelRepository {

    /**
     * 账号-店铺关联 JPA 仓储（定向查询：按账号/账号+店铺查关联）
     */
    private final AccountShopRelJpaRepository jpaRepository;

    /**
     * 账号工厂（绑定创建必须经工厂生成关联 ID）
     */
    private final AccountFactory accountFactory;

    /**
     * 构造账号-店铺关联仓储。
     *
     * @param repository            账号-店铺关联 JPA 仓储
     * @param convertor             DO ↔ PO 转换器
     * @param changeTrackerProvider 变更追踪器提供者
     * @param accountFactory        账号工厂（创建关联实体）
     */
    public AccountShopRelRepositoryImpl(AccountShopRelJpaRepository repository,
                                        RdbGeneralConvertor<AccountShopRel, AccountShopRelPO, Void> convertor,
                                        ChangeTrackerProvider changeTrackerProvider,
                                        AccountFactory accountFactory) {
        super(repository, convertor, changeTrackerProvider);
        this.jpaRepository = repository;
        this.accountFactory = accountFactory;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 单表无子对象：主键即聚合标识。
     */
    @Override
    protected Long retrieveIDFromRoot(AccountShopRel root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 单表无子对象：转换后直接由 JPA 插入。
     */
    @Override
    protected void doInsert(AccountShopRel root) {
        repository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 单表无子对象：由变更后的聚合整行保存（JPA 生成 UPDATE，仅在被追踪时触发）。
     */
    @Override
    protected void doUpdate(AccountShopRel root, ChangeSet changeSet) {
        repository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 真实删除：按主键删除关联行，返回删除条数。
     */
    @Override
    public int delete(AccountShopRel root) {
        return root == null ? 0 : deleteByID(root.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 真实删除：按主键删除关联行，返回删除条数。
     */
    @Override
    public int deleteByID(Long id) {
        if (id == null || !repository.existsById(id)) {
            return 0;
        }
        repository.deleteById(id);
        return 1;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 直接委托 JPA 定向查询（(account_id, shop_id) 联合唯一约束下的集合查询）。
     */
    @Override
    public List<AccountShopRel> findByAccountId(Long accountId) {
        return jpaRepository.findByAccountId(accountId).stream()
                .map(po -> convertor.convertToRoot(po, null))
                .toList();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 幂等绑定：先按账号+店铺查重，已存在直接返回 false；否则经工厂创建关联并
     * saveAndFlush 立即落库（并发重复撞唯一约束时捕获冲突返回 false，视为已存在）。
     */
    @Override
    public boolean bind(Long accountId, Long shopId) {
        final Optional<AccountShopRelPO> existing = jpaRepository.findByAccountIdAndShopId(accountId, shopId);
        if (existing.isPresent()) {
            return false;
        }
        try {
            jpaRepository.saveAndFlush(convertor.convertToPO(
                    accountFactory.createAccountShopRel(accountId, shopId)));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
