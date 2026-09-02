package com.nona.domain.identity.repo;

import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.domain.identity.entity.MerchantApplication;
import com.nona.persistence.BaseRepository;

import java.util.List;
import java.util.Optional;

/**
 * 入驻申请仓储接口（MerchantApplication 聚合持久化契约，实现在基础设施层）。
 * <p>
 * 定位契约：按提交实体（account_id 唯一，至多一行）查当前申请——单行模型
 * 下任意时刻同一账号至多一个申请，pending 唯一性由状态机迁移 +
 * 数据库唯一约束共同承载。平台列表按状态分页（提交时间正序）或全量分页。
 * 写并发串行化：提交/编辑/重提先取账号写锁（{@link #lockAccount}），
 * 审核先取申请行写锁（{@link #lockApplication}）。
 *
 * @author nona9961
 */
public interface MerchantApplicationRepository extends BaseRepository<Long, MerchantApplication> {

    /**
     * 按提交实体查询申请（account_id 唯一约束保证至多一行）。
     *
     * @param accountId 商家账号 ID
     * @return 申请；无申请返回空
     */
    Optional<MerchantApplication> findByAccountId(Long accountId);

    /**
     * 按状态分页列出申请（提交时间正序，先提交的先审）。
     *
     * @param status 申请状态
     * @param offset 首条偏移量（从 0 开始）
     * @param limit  每页条数
     * @return 申请列表；无数据返回空列表
     */
    List<MerchantApplication> listByStatus(ApplicationStatus status, int offset, int limit);

    /**
     * 全量分页列出申请（提交时间正序）。
     *
     * @param offset 首条偏移量（从 0 开始）
     * @param limit  每页条数
     * @return 申请列表；无数据返回空列表
     */
    List<MerchantApplication> listAll(int offset, int limit);

    /**
     * 统计指定状态的申请条数。
     *
     * @param status 申请状态
     * @return 条数
     */
    long countByStatus(ApplicationStatus status);

    /**
     * 统计全部申请条数。
     *
     * @return 条数
     */
    long countAll();

    /**
     * 提交实体维度写锁：对账号行加悲观写锁，串行化同一账号的申请写操作
     * （提交/编辑/重提），one-pending 唯一性在并发窗口下收敛。
     * 由调用方事务持有至提交。
     *
     * @param accountId 商家账号 ID
     */
    void lockAccount(Long accountId);

    /**
     * 申请行写锁：对申请行加悲观写锁，串行化同一申请的审核操作
     * （并发审核只有一个状态迁移生效）。由调用方事务持有至提交。
     *
     * @param applicationId 申请主键
     */
    void lockApplication(Long applicationId);
}