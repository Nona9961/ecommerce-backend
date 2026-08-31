package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.domain.identity.entity.Account;
import com.nona.domain.identity.entity.AccountType;
import com.nona.domain.identity.repo.AccountRepository;
import com.nona.inf.context.ThreadContext;
import com.nona.inf.persistence.converters.RdbGeneralConvertor;
import com.nona.inf.persistence.po.identity.AccountPO;
import com.nona.inf.persistence.repository.jpa.AccountJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 账号仓储落地：{@link DifferRepository} 包装单表 {@code account}，
 * 实现域仓储契约 {@link AccountRepository}。
 * <p>
 * 单表无子对象（地址/收藏/店铺均为独立聚合），转换与持久化均为一行；
 * findByTypeAndUsername 直接委托 JPA 定向查询（(type, username) 联合唯一，
 * 至多一行）；审计时间戳由 JPA auditing 填充。
 *
 * @author nona9961
 */
@Component
public class AccountRepositoryImpl extends DifferRepository<Account, AccountPO, Void>
        implements AccountRepository {

    /**
     * 账号 JPA 仓储（定向查询专用，与父类 repository 同一实例）
     */
    private final AccountJpaRepository jpaRepository;

    /**
     * 构造仓储实现。
     *
     * @param repository            账号 JPA 仓储
     * @param threadContext         请求级上下文（变更追踪器与快照）
     * @param convertor             DO ↔ PO 转换器
     * @param changeTrackerProvider 变更追踪器提供者
     */
    public AccountRepositoryImpl(AccountJpaRepository repository,
                                 ThreadContext threadContext,
                                 RdbGeneralConvertor<Account, AccountPO, Void> convertor,
                                 ChangeTrackerProvider changeTrackerProvider) {
        super(repository, threadContext, convertor, changeTrackerProvider);
        this.jpaRepository = repository;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 直接委托 JPA 定向查询（(type, username) 联合唯一，至多一行）。
     */
    @Override
    public Optional<Account> findByTypeAndUsername(AccountType type, String username) {
        return jpaRepository.findByTypeAndUsername(type, username)
                .map(po -> convertor.convertToRoot(po, null));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Long retrieveIDFromRoot(Account root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 单表无子对象：转换后直接由 JPA 插入。
     */
    @Override
    protected void doInsert(Account root) {
        repository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 单表无子对象：由变更后的聚合整行保存（JPA 生成 UPDATE，仅在被追踪时触发）。
     */
    @Override
    protected void doUpdate(Account root, ChangeSet changeSet) {
        repository.save(convertor.convertToPO(root));
    }
}
