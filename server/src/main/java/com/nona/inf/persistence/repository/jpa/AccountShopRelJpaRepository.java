package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.identity.AccountShopRelPO;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 账号-店铺关联 JPA 仓储（account_shop_rel 表，账号-店铺关联实体）。
 * <p>
 * 采用完整 JPA 仓储接口（ListCrudRepository 超集）：saveAndFlush 用于绑定创建的
 * 并发重复唯一约束冲突即时捕获（延迟 flush 会使冲突越过仓储捕获点，幂等兜底失效）。
 *
 * @author nona9961
 */
public interface AccountShopRelJpaRepository extends JpaRepository<AccountShopRelPO, Long> {

    /**
     * 按账号 ID 查询全部店铺关联。
     *
     * @param accountId 账号 ID
     * @return 关联列表；无关联返回空列表
     */
    List<AccountShopRelPO> findByAccountId(Long accountId);

    /**
     * 按账号 ID + 店铺 ID 查询关联（联合唯一约束保证至多一行）。
     *
     * @param accountId 账号 ID
     * @param shopId    店铺 ID
     * @return 关联；不存在返回空
     */
    Optional<AccountShopRelPO> findByAccountIdAndShopId(Long accountId, Long shopId);
}
