package com.nona.domain.identity.repo;

import com.nona.domain.identity.entity.AccountShopRel;
import com.nona.persistence.BaseRepository;

import java.util.List;

/**
 * 账号-店铺关联仓储接口（Account 聚合内关联实体）：关联的持久化契约，实现在基础设施层
 * （由 DifferRepository 包装 account_shop_rel 表落地，随入驻审核通过时写入；
 * 登录链路经 JPA 直接读取）。
 *
 * @author nona9961
 */
public interface AccountShopRelRepository extends BaseRepository<Long, AccountShopRel> {

    /**
     * 按账号 ID 查询全部店铺关联。
     *
     * @param accountId 账号 ID
     * @return 店铺关联列表；无关联返回空列表
     */
    List<AccountShopRel> findByAccountId(Long accountId);
}
