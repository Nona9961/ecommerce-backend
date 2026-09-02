package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.identity.AddressBookPO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;

/**
 * 地址簿主表 JPA 仓储（address_book 表，聚合根根行；id = 簿独立主键）。
 *
 * @author nona9961
 */
public interface AddressBookJpaRepository extends ListCrudRepository<AddressBookPO, Long> {

    /**
     * 按业务关联账号查询地址簿（account_id 唯一，至多一行）。
     *
     * @param accountId 买家账号 ID
     * @return 地址簿根行；无簿返回空
     */
    Optional<AddressBookPO> findByAccountId(Long accountId);
}