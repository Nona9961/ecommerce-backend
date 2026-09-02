package com.nona.domain.identity.repo;

import com.nona.domain.identity.entity.AddressBook;
import com.nona.persistence.BaseRepository;

/**
 * 地址簿仓储接口：买家地址簿的持久化契约（address_book 主表 + address 从表，买家维度）。
 * <p>
 * 实现在基础设施层（AddressBookRepositoryImpl 继承 DifferRepository：主表快照追踪 + 变更集驱动落库）；
 * 领域层只依赖本契约，不感知 JPA。聚合根主表以独立主键（bookId）承载簿身份，account_id 为
 * 业务关联列（一个买家一本簿）；地址行以 book_id（rootId）关联聚合根。
 *
 * @author nona9961
 */
public interface AddressBookRepository extends BaseRepository<Long, AddressBook> {

    /**
     * 按买家账号加载地址簿：无任何地址时返回空簿（聚合始终存在，不返回 null）。
     *
     * @param accountId 买家账号 ID
     * @return 地址簿（可能为空）
     */
    AddressBook getByAccountId(Long accountId);

    /**
     * 保存地址簿：变更集驱动落库，新增/更新/删除最小化写库。
     *
     * @param book 地址簿
     * @return 是否有行级变更被持久化
     */
    @Override
    boolean save(AddressBook book);

    /**
     * 买家维度悲观锁：对账号行加写锁，串行化同一买家的地址写操作。
     * <p>
     * 默认地址唯一性跨行成立（同买家多行），行级锁与唯一索引均无法在
     * 「簿内无默认行的并发窗口」提供完整保护——以账号行锁串行化同买家全部
     * 地址写事务，默认迁移（清旧置新）在锁内完成，两个并发设置默认最终收敛为
     * 恰好一个默认。锁由调用方事务持有至提交（调用方需处于活动事务中）。
     *
     * @param accountId 买家账号 ID
     */
    void lockBuyer(Long accountId);
}