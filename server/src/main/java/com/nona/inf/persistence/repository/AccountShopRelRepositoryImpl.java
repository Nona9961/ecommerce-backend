package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.domain.identity.entity.AccountShopRel;
import com.nona.domain.identity.repo.AccountShopRelRepository;
import com.nona.inf.context.ThreadContext;
import com.nona.inf.persistence.converters.RdbGeneralConvertor;
import com.nona.inf.persistence.po.identity.AccountShopRelPO;
import com.nona.inf.persistence.repository.jpa.AccountShopRelJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 账号-店铺关联仓储落地：{@link DifferRepository} 包装单表 account_shop_rel，
 * 实现域仓储契约 {@link AccountShopRelRepository}（Account 聚合内关联实体，
 * 单行实体无子对象；入驻审核通过时写入，登录链路经 findByAccountId 读取）。
 * <p>
 * 变更追踪：读 → track 快照 → 保存 （新增 doInsert / 属性变更 doUpdate）
 * 由 changeTracking 框架计算变更集驱动，属性级 diff 落库。
 *
 * @author nona9961
 */
@Component
public class AccountShopRelRepositoryImpl
        extends DifferRepository<AccountShopRel, AccountShopRelPO, Void>
        implements AccountShopRelRepository {

    /**
     * 账号-店铺关联 JPA 仓储（定向查询：按账号查关联）
     */
    private final AccountShopRelJpaRepository jpaRepository;

    /**
     * 构造账号-店铺关联仓储。
     *
     * @param repository            账号-店铺关联 JPA 仓储
     * @param threadContext         请求级上下文（变更追踪器与快照）
     * @param convertor             DO ↔ PO 转换器
     * @param changeTrackerProvider 变更追踪器提供者
     */
    public AccountShopRelRepositoryImpl(AccountShopRelJpaRepository repository,
                                        ThreadContext threadContext,
                                        RdbGeneralConvertor<AccountShopRel, AccountShopRelPO, Void> convertor,
                                        ChangeTrackerProvider changeTrackerProvider) {
        super(repository, threadContext, convertor, changeTrackerProvider);
        this.jpaRepository = repository;
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
}