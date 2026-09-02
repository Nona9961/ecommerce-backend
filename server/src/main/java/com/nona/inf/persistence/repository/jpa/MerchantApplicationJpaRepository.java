package com.nona.inf.persistence.repository.jpa;

import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.inf.persistence.po.identity.AccountPO;
import com.nona.inf.persistence.po.identity.MerchantApplicationPO;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 入驻申请 JPA 仓储（onboarding_application 表，global）：按提交实体定向查询、
 * 按状态/全量分页（提交时间倒序由调用方排序）、审核行写锁与账号行写锁。
 * <p>
 * 锁语义：{@link #lockApplication} 对申请行加悲观写锁（审核并发串行化）；
 * {@link #lockAccount} 借道账号表行加锁（商家必然存在，登录链路保证）——
 * 申请行可能尚未创建（首次提交）时仍能提供稳定的账号级串行化点，
 * 是 one-pending 唯一性在并发窗口下的最终防线（唯一约束兜底）。
 *
 * @author nona9961
 */
public interface MerchantApplicationJpaRepository extends JpaRepository<MerchantApplicationPO, Long> {

    /**
     * 按提交实体查询申请（account_id 唯一约束保证至多一行）。
     *
     * @param accountId 商家账号 ID
     * @return 申请；无申请返回空
     */
    Optional<MerchantApplicationPO> findByAccountId(Long accountId);

    /**
     * 按状态分页查询申请（排序由调用方指定，平台列表固定提交时间倒序）。
     *
     * @param status   申请状态
     * @param pageable 分页与排序
     * @return 分页申请
     */
    Page<MerchantApplicationPO> findByStatus(ApplicationStatus status, Pageable pageable);

    /**
     * 按状态统计申请条数（列表分页 total）。
     *
     * @param status 申请状态
     * @return 条数
     */
    long countByStatus(ApplicationStatus status);

    /**
     * 申请行写锁：对申请行加悲观写锁（for update），串行化同一申请的审核操作。
     *
     * @param applicationId 申请主键
     * @return 申请行（不存在返回空；正常路径恒存在）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MerchantApplicationPO m where m.id = :applicationId")
    Optional<MerchantApplicationPO> lockApplication(@Param("applicationId") Long applicationId);

    /**
     * 账号行写锁：借道账号表行加悲观写锁（for update），串行化同一账号的
     * 申请写操作（提交/编辑/重提）；申请行未创建时仍有稳定的账号级串行化点。
     *
     * @param accountId 商家账号 ID
     * @return 账号行（不存在返回空；正常路径恒存在）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountPO a where a.id = :accountId")
    Optional<AccountPO> lockAccount(@Param("accountId") Long accountId);
}