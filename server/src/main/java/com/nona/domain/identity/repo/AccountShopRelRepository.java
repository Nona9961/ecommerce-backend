package com.nona.domain.identity.repo;

import com.nona.domain.identity.entity.AccountShopRel;
import com.nona.persistence.BaseRepository;

import java.util.List;
import java.util.Optional;

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

    /**
     * 按店铺 ID 反查关联（店铺归属账号定位；每商家一行有且仅有一家
     * 店，shop_id 至多一行关联——搜索写后窗口等消费方按归属账号打标）。
     *
     * @param shopId 店铺 ID
     * @return 关联；无关联返回空
     */
    Optional<AccountShopRel> findByShopId(Long shopId);

    /**
     * 绑定账号-店铺关联（幂等）：不存在该关联时创建并落库，已存在则直接返回
     * （不重复创建）；并发重复由 (account_id, shop_id) 联合唯一约束兜底，
     * 冲突视为已存在。绑定后经 {@link #findByAccountId} 立即可见。
     *
     * @param accountId 账号 ID
     * @param shopId    店铺 ID
     * @return 本次是否新建了关联（true=新建，false=已存在）
     */
    boolean bind(Long accountId, Long shopId);
}
