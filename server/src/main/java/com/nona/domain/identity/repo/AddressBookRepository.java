package com.nona.domain.identity.repo;

import com.nona.domain.identity.entity.AddressBook;
import com.nona.persistence.BaseRepository;

/**
 * 地址簿仓储接口：买家地址簿的持久化契约（address 表，每行一个地址，买家维度）。
 * <p>
 * 实现在基础设施层（AddressBookRepositoryImpl 以 JPA 行级同步 address 表落地）；
 * 领域层只依赖本契约，不感知 JPA。地址簿是单表集合形态（无「聚合根一行」的主表），
 * 故不套用 DifferRepository 的主表快照模板：读取按买家全量加载，保存按行级 diff 同步。
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
     * 保存地址簿：与库内现状做行级 diff，新增/更新/删除最小化落库。
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