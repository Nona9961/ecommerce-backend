package com.nona.inf.persistence.identity;

import com.nona.domain.identity.entity.AccountStatus;
import com.nona.domain.identity.entity.AccountType;
import com.nona.inf.persistence.po.identity.AccountPO;
import com.nona.inf.persistence.po.identity.AccountShopRelPO;
import com.nona.inf.persistence.repository.jpa.AccountJpaRepository;
import com.nona.inf.persistence.repository.jpa.AccountShopRelJpaRepository;
import com.nona.inf.security.AccountStatusProvider;
import com.nona.inf.security.AuthUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 账号状态 DB SPI（JPA 实现）单元测试：按 uid 查单表与上下文组装语义
 * （Phase 1 骨架态，预期红）。
 * <p>
 * 断言对准回填契约（单表 account，type 区分角色）：买家 → BUYER 角色、shopIds 恒空；
 * 商家 → SELLER 角色 + 关联店铺列表；平台运营（portal=ADMIN）无落点——单表不存在
 * ADMIN 类型账号，查询返回空（fail-closed）；uid 不存在 → 空（过滤器按未认证处理）。
 */
class JpaAccountStatusProviderTest {

    /**
     * 买家账号（uid 1001）
     */
    private static final long BUYER_UID = 1001L;

    /**
     * 被封禁买家（uid 1002）
     */
    private static final long BANNED_BUYER_UID = 1002L;

    /**
     * 商家账号（uid 2001，关联店铺 3001）
     */
    private static final long SELLER_UID = 2001L;

    /**
     * 平台运营视角（portal=ADMIN）：单表无 admin 账号落点，查询必须返回空
     */
    private static final long ADMIN_UID = 2002L;

    /**
     * 不存在的 uid
     */
    private static final long ABSENT_UID = 9999L;

    /**
     * mock 的账号仓储（单表）
     */
    private AccountJpaRepository accountRepository;

    /**
     * mock 的账号-店铺关联仓储
     */
    private AccountShopRelJpaRepository accountShopRelRepository;

    /**
     * 被测提供器
     */
    private AccountStatusProvider provider;

    /**
     * 初始化 mock 与被测对象。
     */
    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountJpaRepository.class);
        accountShopRelRepository = mock(AccountShopRelJpaRepository.class);
        provider = new JpaAccountStatusProvider(accountRepository, accountShopRelRepository);
    }

    /**
     * 买家账号：命中返回 ACTIVE + BUYER 角色 + 空店铺列表。
     */
    @Test
    void loadUserContext_buyerAccount_returnsBuyerContext() {
        when(accountRepository.findById(BUYER_UID))
                .thenReturn(java.util.Optional.of(accountPo(BUYER_UID, "buyer-alice",
                        AccountStatus.NORMAL, AccountType.BUYER)));

        final var loaded = provider.loadUserContext(BUYER_UID);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().status()).isEqualTo(com.nona.inf.security.AccountStatus.ACTIVE);
        assertThat(loaded.get().roles()).containsExactly("BUYER");
        assertThat(loaded.get().shopIds()).isEmpty();
    }

    /**
     * 买家封禁：状态透传 BANNED（登录拒绝 + 过滤器 403 的裁决依据）。
     */
    @Test
    void loadUserContext_bannedBuyer_returnsBanned() {
        when(accountRepository.findById(BANNED_BUYER_UID))
                .thenReturn(java.util.Optional.of(accountPo(BANNED_BUYER_UID, "banned-buyer",
                        AccountStatus.BANNED, AccountType.BUYER)));

        final var loaded = provider.loadUserContext(BANNED_BUYER_UID);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().status()).isEqualTo(com.nona.inf.security.AccountStatus.BANNED);
    }

    /**
     * 商家账号：SELLER 角色 + 关联店铺列表（M10 rel 填充 shopIds）。
     */
    @Test
    void loadUserContext_sellerAccount_returnsSellerContextWithShopIds() {
        when(accountRepository.findById(SELLER_UID))
                .thenReturn(java.util.Optional.of(accountPo(SELLER_UID, "seller-bob",
                        AccountStatus.NORMAL, AccountType.SELLER)));
        when(accountShopRelRepository.findByAccountId(SELLER_UID))
                .thenReturn(java.util.List.of(relPo(9001L, SELLER_UID, 3001L)));

        final var loaded = provider.loadUserContext(SELLER_UID);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().status()).isEqualTo(com.nona.inf.security.AccountStatus.ACTIVE);
        assertThat(loaded.get().roles()).containsExactly("SELLER");
        assertThat(loaded.get().shopIds()).containsExactly(3001L);
    }

    /**
     * 平台运营（portal=ADMIN）：单表无 admin 落点（RBAC 属 WU-10/Phase-II），
     * 查询返回空 → 过滤器按未认证处理（fail-closed，登录拒绝）。
     */
    @Test
    void loadUserContext_adminUid_returnsEmpty() {
        when(accountRepository.findById(ADMIN_UID)).thenReturn(java.util.Optional.empty());

        assertThat(provider.loadUserContext(ADMIN_UID)).isEmpty();
    }

    /**
     * 单表无此 uid：返回空（过滤器按未认证处理，fail-closed）。
     */
    @Test
    void loadUserContext_absentUid_returnsEmpty() {
        when(accountRepository.findById(ABSENT_UID)).thenReturn(java.util.Optional.empty());

        assertThat(provider.loadUserContext(ABSENT_UID)).isEmpty();
    }

    /**
     * 构造账号 PO（单表行）。
     *
     * @param id       账号 ID
     * @param username 用户名
     * @param status   状态
     * @param type     账号类型
     * @return PO
     */
    private static AccountPO accountPo(Long id, String username, AccountStatus status, AccountType type) {
        final AccountPO po = new AccountPO();
        po.setId(id);
        po.setUsername(username);
        po.setPasswordHash("hash");
        po.setStatus(status);
        po.setType(type);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());
        return po;
    }

    /**
     * 构造关联 PO。
     *
     * @param id        关联 ID
     * @param accountId 账号 ID
     * @param shopId    店铺 ID
     * @return PO
     */
    private static AccountShopRelPO relPo(Long id, Long accountId, Long shopId) {
        final AccountShopRelPO po = new AccountShopRelPO();
        po.setId(id);
        po.setAccountId(accountId);
        po.setShopId(shopId);
        po.setCreateTime(LocalDateTime.now());
        po.setUpdateTime(LocalDateTime.now());
        return po;
    }
}
