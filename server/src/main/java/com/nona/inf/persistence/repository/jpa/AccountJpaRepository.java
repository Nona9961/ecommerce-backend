package com.nona.inf.persistence.repository.jpa;

import com.nona.domain.identity.entity.AccountType;
import com.nona.inf.persistence.po.identity.AccountPO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;

/**
 * 账号 JPA 仓储（单表 {@code account}，type 列区分买家/商家）。
 *
 * @author nona9961
 */
public interface AccountJpaRepository extends ListCrudRepository<AccountPO, Long> {

    /**
     * 按类型 + 用户名查询账号（(type, username) 联合唯一约束保证至多一行）。
     *
     * @param type     账号类型
     * @param username 用户名
     * @return 账号；不存在返回空
     */
    Optional<AccountPO> findByTypeAndUsername(AccountType type, String username);
}
