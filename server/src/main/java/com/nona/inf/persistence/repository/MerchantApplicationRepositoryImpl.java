package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.domain.identity.entity.MerchantApplication;
import com.nona.domain.identity.repo.MerchantApplicationRepository;
import com.nona.inf.persistence.converters.MerchantApplicationConvertor;
import com.nona.inf.persistence.po.identity.MerchantApplicationPO;
import com.nona.inf.persistence.repository.jpa.MerchantApplicationJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 入驻申请仓储落地：{@link DifferRepository} 包装单表 {@code onboarding_application}（global），
 * 实现域仓储契约 {@link MerchantApplicationRepository}。
 * <p>
 * 单行无子对象（资料/状态/审核字段均为主表列），转换与持久化均为一行；
 * 经模板读 → track 快照 → 变更集驱动落库（状态迁移/资料编辑 = 整行更新）。
 * 按账号定向查询直接委托 JPA（account_id 唯一，至多一行）；平台列表按
 * 状态/全量分页（提交时间正序）。写锁经 JPA 悲观锁方法（账号行 / 申请行
 * for update），由用例事务持有至提交。删除语义真实化（返回实际删除行数）。
 *
 * @author nona9961
 */
@Component
public class MerchantApplicationRepositoryImpl
        extends DifferRepository<MerchantApplication, MerchantApplicationPO, Void>
        implements MerchantApplicationRepository {

    /**
     * 申请 JPA 仓储（定向查询/分页/写锁）
     */
    private final MerchantApplicationJpaRepository jpaRepository;

    /**
     * 构造仓储实现。
     *
     * @param repository            申请 JPA 仓储
     * @param convertor             DO ↔ PO 转换器
     * @param changeTrackerProvider 变更追踪器提供者
     */
    public MerchantApplicationRepositoryImpl(MerchantApplicationJpaRepository repository,
                                             MerchantApplicationConvertor convertor,
                                             ChangeTrackerProvider changeTrackerProvider) {
        super(repository, convertor, changeTrackerProvider);
        this.jpaRepository = repository;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 直接委托 JPA 定向查询（account_id 唯一，至多一行）。
     */
    @Override
    public Optional<MerchantApplication> findByAccountId(Long accountId) {
        return jpaRepository.findByAccountId(accountId)
                .map(po -> convertor.convertToRoot(po, null));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 偏移量换算页码，排序固定提交时间正序（先提交的先审）。
     */
    @Override
    public List<MerchantApplication> listByStatus(ApplicationStatus status, int offset, int limit) {
        final Page<MerchantApplicationPO> page =
                jpaRepository.findByStatus(status, pageRequest(offset, limit));
        return page.getContent().stream().map(po -> convertor.convertToRoot(po, null)).toList();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 全量分页委托 JPA findAll(Pageable)，排序固定提交时间正序。
     */
    @Override
    public List<MerchantApplication> listAll(int offset, int limit) {
        final Page<MerchantApplicationPO> page = jpaRepository.findAll(pageRequest(offset, limit));
        return page.getContent().stream().map(po -> convertor.convertToRoot(po, null)).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countByStatus(ApplicationStatus status) {
        return jpaRepository.countByStatus(status);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countAll() {
        return jpaRepository.count();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 账号行悲观写锁（for update）：串行化同一账号的申请写操作；
     * 申请行未创建（首次提交）时仍有稳定的账号级串行化点。
     */
    @Override
    public void lockAccount(Long accountId) {
        jpaRepository.lockAccount(accountId);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 申请行悲观写锁（for update）：串行化同一申请的审核操作。
     */
    @Override
    public void lockApplication(Long applicationId) {
        jpaRepository.lockApplication(applicationId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Long retrieveIDFromRoot(MerchantApplication root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 单表无子对象：转换后直接由 JPA 插入。
     */
    @Override
    protected void doInsert(MerchantApplication root) {
        repository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 单表无子对象：由变更后的聚合整行保存（JPA 生成 UPDATE，仅在被追踪时触发）。
     */
    @Override
    protected void doUpdate(MerchantApplication root, ChangeSet changeSet) {
        repository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 按申请删除（返回真实删除条数）。申请持久化无删除业务入口（状态机无删除态），
     * 实现真实语义供仓储契约完备（测试数据清理路径）。
     */
    @Override
    public int delete(MerchantApplication application) {
        return application == null ? 0 : deleteByID(application.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 按主键删除：存在才删，返回真实影响行数（0/1，非契约形）。
     */
    @Override
    public int deleteByID(Long applicationId) {
        if (applicationId == null) {
            return 0;
        }
        if (!jpaRepository.existsById(applicationId)) {
            return 0;
        }
        jpaRepository.deleteById(applicationId);
        return 1;
    }

    /**
     * 组装分页请求（提交时间正序，先提交的先审）。
     *
     * @param offset 首条偏移量
     * @param limit  每页条数
     * @return 分页请求
     */
    private static PageRequest pageRequest(int offset, int limit) {
        return PageRequest.of(offset / limit, limit, Sort.by(Sort.Direction.ASC, "createTime"));
    }
}