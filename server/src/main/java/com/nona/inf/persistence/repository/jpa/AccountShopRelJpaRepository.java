package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.identity.AccountShopRelPO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

/**
 * 账号-店铺关联 JPA 仓储（account_shop_rel 表，M10）。
 *
 * @author nona9961
 */
public interface AccountShopRelJpaRepository extends ListCrudRepository<AccountShopRelPO, Long> {

    /**
     * 按账号 ID 查询全部店铺关联。
     *
     * @param accountId 账号 ID
     * @return 关联列表；无关联返回空列表
     */
    List<AccountShopRelPO> findByAccountId(Long accountId);
}
