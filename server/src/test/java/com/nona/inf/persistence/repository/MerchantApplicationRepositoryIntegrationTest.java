package com.nona.inf.persistence.repository;

import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.domain.identity.entity.MerchantApplication;
import com.nona.domain.identity.factory.MerchantApplicationFactory;
import com.nona.domain.identity.repo.MerchantApplicationRepository;
import com.nona.inf.persistence.repository.jpa.MerchantApplicationJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入驻申请仓储集成测试：聚合根主表（onboarding_application）存在性、
 * 变更集驱动落库（状态迁移/资料编辑）、account_id 唯一约束（one-pending 的
 * 数据库兜底）、平台分页列表、写锁方法。
 * <p>
 * 与脚手架的 DDD 红线对应：聚合根必须有根表（onboarding_application 一行
 * = 一个申请实例，主键 = 申请独立主键）；仓储继承 DifferRepository，
 * 读 = track 快照、save = 变更集驱动落库；审核字段（状态/原因/审核人/时间）
 * 均为主表自带列（无独立审核工单表）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class MerchantApplicationRepositoryIntegrationTest {

    /**
     * 入驻申请仓储（被测对象：DifferRepository 子类）
     */
    @Autowired
    private MerchantApplicationRepository applicationRepository;

    /**
     * 申请主表 JPA（断言根表行）
     */
    @Autowired
    private MerchantApplicationJpaRepository applicationJpaRepository;

    /**
     * 申请工厂
     */
    @Autowired
    private MerchantApplicationFactory applicationFactory;

    /**
     * 编程式事务模板（悲观锁查询必须在活动事务内）
     */
    @Autowired
    private TransactionTemplate tx;

    /**
     * 每用例前清空申请表。
     */
    @BeforeEach
    void setUp() {
        applicationJpaRepository.deleteAll();
    }

    /**
     * 新增：save 后主表存在一根行，聚合可整体读回（状态/资料/审核字段齐全）。
     */
    @Test
    @DisplayName("新增申请根行落库并可读回")
    void save_insertsRootRow() {
        final MerchantApplication application = applicationFactory.create(100L, "示例店铺", "张三", "13800138000");
        applicationRepository.save(application);

        assertThat(applicationJpaRepository.existsById(application.getId())).isTrue();

        final MerchantApplication loaded = applicationRepository.getByID(application.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getAccountId()).isEqualTo(100L);
        assertThat(loaded.getShopName()).isEqualTo("示例店铺");
        assertThat(loaded.getContactName()).isEqualTo("张三");
        assertThat(loaded.getContactPhone()).isEqualTo("13800138000");
        assertThat(loaded.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(loaded.getCreateTime()).isNotNull();
        assertThat(loaded.getRejectReason()).isNull();
        assertThat(loaded.getReviewerId()).isNull();
        assertThat(loaded.getReviewTime()).isNull();
    }

    /**
     * 更新：状态迁移 + 审核字段落位经变更集整行落库，重新读回一致。
     */
    @Test
    @DisplayName("状态迁移变更集驱动落库")
    void save_updatesStatusAndReviewFields() {
        final MerchantApplication application = applicationFactory.create(100L, "示例店铺", "张三", "13800138000");
        applicationRepository.save(application);
        final long applicationId = application.getId();

        final MerchantApplication loaded = applicationRepository.getByID(applicationId);
        loaded.reject(9001L, "资料不完整");
        applicationRepository.save(loaded);

        final MerchantApplication reloaded = applicationRepository.getByID(applicationId);
        assertThat(reloaded.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(reloaded.getRejectReason()).isEqualTo("资料不完整");
        assertThat(reloaded.getReviewerId()).isEqualTo(9001L);
        assertThat(reloaded.getReviewTime()).isNotNull();
    }

    /**
     * 唯一性：同一账号重复插入第二行被唯一约束拒绝（one-pending 数据库兜底，
     * 应用层账号锁串行化之外的并发防线）。
     */
    @Test
    @DisplayName("同账号二次插入唯一约束拒绝")
    void save_duplicateAccount_rejectsByUniqueConstraint() {
        applicationRepository.save(applicationFactory.create(100L, "店铺一", "张三", "13800138000"));

        assertThatThrownBy(() ->
                applicationRepository.save(applicationFactory.create(100L, "店铺二", "李四", "13900139000")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(applicationRepository.findByAccountId(100L)).isPresent();
        assertThat(applicationJpaRepository.count()).isEqualTo(1);
    }

    /**
     * 更新：重提回 pending 并清空审核结论经变更集落库。
     */
    @Test
    @DisplayName("重提清空审核结论落库")
    void save_resubmitClearsReviewFields() {
        final MerchantApplication application = applicationFactory.create(100L, "示例店铺", "张三", "13800138000");
        applicationRepository.save(application);
        final long applicationId = application.getId();
        MerchantApplication loaded = applicationRepository.getByID(applicationId);
        loaded.reject(9001L, "资料不完整");
        applicationRepository.save(loaded);

        loaded = applicationRepository.getByID(applicationId);
        loaded.resubmit("新店名", "李四", "13900139000");
        applicationRepository.save(loaded);

        final MerchantApplication reloaded = applicationRepository.getByID(applicationId);
        assertThat(reloaded.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(reloaded.getShopName()).isEqualTo("新店名");
        assertThat(reloaded.getRejectReason()).isNull();
        assertThat(reloaded.getReviewerId()).isNull();
        assertThat(reloaded.getReviewTime()).isNull();
    }

    /**
     * 分页：按状态分页（提交时间正序）+ 全量分页，总数正确。
     */
    @Test
    @DisplayName("按状态与全量分页查询")
    void list_pagedByStatusAndAll() {
        for (long i = 0; i < 3; i++) {
            applicationRepository.save(applicationFactory.create(200L + i, "店铺" + i, "张三", "13800138000"));
        }

        assertThat(applicationRepository.listByStatus(ApplicationStatus.PENDING, 0, 2)).hasSize(2);
        assertThat(applicationRepository.countByStatus(ApplicationStatus.PENDING)).isEqualTo(3);
        assertThat(applicationRepository.listAll(0, 10)).hasSize(3);
        assertThat(applicationRepository.countAll()).isEqualTo(3);
        final List<MerchantApplication> secondPage = applicationRepository.listAll(2, 2);
        assertThat(secondPage).hasSize(1);
    }

    /**
     * 写锁：账号行锁与申请行锁在事务内可用（锁方法不抛错、不破坏后续读）。
     */
    @Test
    @DisplayName("账号锁与申请锁可用")
    void locks_availableInTransaction() {
        final MerchantApplication application = applicationFactory.create(100L, "示例店铺", "张三", "13800138000");
        applicationRepository.save(application);

        tx.executeWithoutResult(status -> {
            applicationRepository.lockAccount(100L);
            applicationRepository.lockApplication(application.getId());
        });

        final Optional<MerchantApplication> loaded = applicationRepository.findByAccountId(100L);
        assertThat(loaded).isPresent();
    }
}