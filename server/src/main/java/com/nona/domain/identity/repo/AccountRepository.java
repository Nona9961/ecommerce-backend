package com.nona.domain.identity.repo;

import com.nona.domain.identity.entity.Account;
import com.nona.domain.identity.entity.AccountType;
import com.nona.persistence.BaseRepository;

import java.util.Optional;

/**
 * 账号仓储接口：单表 {@code account} 的持久化契约（买家/商家统一），
 * 实现在基础设施层（AccountRepositoryImpl 以 DifferRepository 包装 account 表落地）。
 *
 * @author nona9961
 */
public interface AccountRepository extends BaseRepository<Long, Account> {

    /**
     * 按类型 + 用户名查询账号（(type, username) 联合唯一，登录定向查询路径）。
     *
     * @param type     账号类型
     * @param username 用户名
     * @return 账号；不存在返回空
     */
    Optional<Account> findByTypeAndUsername(AccountType type, String username);
}
