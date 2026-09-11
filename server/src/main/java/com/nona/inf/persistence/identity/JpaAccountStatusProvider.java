package com.nona.inf.persistence.identity;

import com.nona.domain.identity.entity.AccountStatus;
import com.nona.domain.identity.entity.AccountType;
import com.nona.inf.persistence.po.identity.AccountPO;
import com.nona.inf.persistence.po.identity.AccountShopRelPO;
import com.nona.inf.persistence.repository.jpa.AccountJpaRepository;
import com.nona.inf.persistence.repository.jpa.AccountShopRelJpaRepository;
import com.nona.inf.security.AccountStatusProvider;
import com.nona.inf.security.AuthUserContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 账号状态 DB SPI 的 JPA 实现：按 uid 查单表 {@code account} 组装
 * {@link AuthUserContext}（状态/角色/店铺列表），供认证过滤器缓存 miss 回填。
 * <p>
 * 单表语义（领域模型：账号聚合仅承载凭证与状态）：账号只有一张表，type 列区分买家/商家——
 * 角色由 type 定向推导（BUYER → [BUYER]、SELLER → [SELLER]），商家附带读取
 * account_shop_rel 填充 shopIds（当前恒 1 个）；<b>平台运营（admin）账号
 * 无落点</b>（RBAC 扩展位），本表不存在 ADMIN 类型，portal=ADMIN
 * 的查询定向不到任何账号 → 返回空（过滤器按未认证处理，fail-closed）。
 *
 * @author nona9961
 */
@Component
public class JpaAccountStatusProvider implements AccountStatusProvider {

    /**
     * 账号 JPA 仓储（单表）
     */
    private final AccountJpaRepository accountRepository;

    /**
     * 账号-店铺关联 JPA 仓储（回填 shopIds）
     */
    private final AccountShopRelJpaRepository accountShopRelRepository;

    /**
     * 构造 JPA 回填实现。
     *
     * @param accountRepository        账号仓储（单表）
     * @param accountShopRelRepository 账号-店铺关联仓储
     */
    public JpaAccountStatusProvider(AccountJpaRepository accountRepository,
                                    AccountShopRelJpaRepository accountShopRelRepository) {
        this.accountRepository = accountRepository;
        this.accountShopRelRepository = accountShopRelRepository;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 按 uid 查单表：角色由 type 定向推导（BUYER/SELLER），商家账号附带读取
     * account_shop_rel 填充 shopIds（当前恒 1 个）；域状态映射安全链状态
     * （NORMAL → ACTIVE、BANNED → BANNED）；查无账号返回空（fail-closed，
     * 含 portal=ADMIN 场景——单表无 admin 落点）。
     */
    @Override
    public Optional<AuthUserContext> loadUserContext(Long uid) {
        final Optional<AccountPO> loaded = accountRepository.findById(uid);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        final AccountPO account = loaded.get();
        final com.nona.inf.security.AccountStatus status = switch (account.getStatus()) {
            case NORMAL -> com.nona.inf.security.AccountStatus.ACTIVE;
            case BANNED -> com.nona.inf.security.AccountStatus.BANNED;
        };
        final List<String> roles = List.of(account.getType().name());
        final List<Long> shopIds = account.getType() == AccountType.SELLER
                ? accountShopRelRepository.findByAccountId(uid).stream()
                        .map(AccountShopRelPO::getShopId)
                        .toList()
                : List.of();
        return Optional.of(new AuthUserContext(status, roles, shopIds));
    }
}
