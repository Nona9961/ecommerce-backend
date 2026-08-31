package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.identity.AccountPO;
import com.nona.inf.persistence.po.identity.AddressPO;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 收货地址 JPA 仓储（address 表，买家维度行集合）。
 *
 * @author nona9961
 */
public interface AddressJpaRepository extends ListCrudRepository<AddressPO, Long> {

    /**
     * 按归属买家查询全部地址（按 ID 升序，删默认后的自动提升以最早一条为准）。
     *
     * @param accountId 买家账号 ID
     * @return 地址列表；无地址返回空列表
     */
    List<AddressPO> findByAccountIdOrderByIdAsc(Long accountId);

    /**
     * 买家维度写锁：对账号行加悲观写锁（for update），串行化同一买家的地址写事务。
     * <p>
     * 查询借道账号表行（买家必然存在，登录链路保证）——地址簿为空簿（无行可锁）时
     * 仍能提供稳定的买家级串行化点，是默认地址唯一性在并发窗口下的最终防线。
     *
     * @param accountId 买家账号 ID
     * @return 账号行（不存在返回空；正常路径恒存在）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountPO a where a.id = :accountId")
    Optional<AccountPO> lockBuyerAccount(@Param("accountId") Long accountId);
}