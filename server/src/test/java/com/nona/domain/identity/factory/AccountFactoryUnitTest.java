package com.nona.domain.identity.factory;

import com.nona.domain.identity.entity.Account;
import com.nona.domain.identity.entity.AccountShopRel;
import com.nona.domain.identity.entity.AccountStatus;
import com.nona.domain.identity.entity.AccountType;
import com.nona.exceptions.BusinessException;
import com.nona.inf.security.BcryptCredentialService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账号工厂单元测试：聚合创建与不变量初始化（单表 account 语义，type 区分买家/商家）。
 * <p>
 * 成功路径依赖凭证端口；纯拼装路径（类型校验、关联创建）
 * 随工厂真实实现转绿。平台运营（admin）账号无落点（RBAC 扩展位），
 * 工厂不接受 ADMIN——AccountType 枚举本身不存在该值，编译期即排除。
 */
class AccountFactoryUnitTest {

    /**
     * 被测工厂
     */
    private final AccountFactory factory = new AccountFactory();

    /**
     * 凭证端口（BCrypt 落地）
     */
    private final BcryptCredentialService credentialService = new BcryptCredentialService();

    /**
     * 创建买家账号（BUYER）：ID 生成、用户名/摘要/初始状态/类型正确。
     */
    @Test
    void createAccount_buyer_initializesAccount() {
        final Account account =
                factory.createAccount(AccountType.BUYER, "alice", "password123", credentialService);
        assertThat(account.getId()).isNotNull();
        assertThat(account.getUsername()).isEqualTo("alice");
        assertThat(account.getPasswordHash()).isNotEqualTo("password123");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.NORMAL);
        assertThat(account.getType()).isEqualTo(AccountType.BUYER);
    }

    /**
     * 创建商家账号（SELLER）：type 正确写入聚合。
     */
    @Test
    void createAccount_seller_setsType() {
        final Account account =
                factory.createAccount(AccountType.SELLER, "shop-owner", "password123", credentialService);
        assertThat(account.getUsername()).isEqualTo("shop-owner");
        assertThat(account.getType()).isEqualTo(AccountType.SELLER);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.NORMAL);
    }

    /**
     * 账号类型为空 → 业务拒绝（类型是聚合不变量，先于凭证处理校验）。
     */
    @Test
    void createAccount_nullType_rejects() {
        assertThatThrownBy(() -> factory.createAccount(null, "alice", "password123", credentialService))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号类型");
    }

    /**
     * 创建账号：用户名空白 → 业务拒绝。
     */
    @Test
    void createAccount_blankUsername_rejects() {
        assertThatThrownBy(() ->
                factory.createAccount(AccountType.BUYER, "  ", "password123", credentialService))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名");
    }

    /**
     * 创建账号：密码空白 → 业务拒绝。
     */
    @Test
    void createAccount_blankPassword_rejects() {
        assertThatThrownBy(() ->
                factory.createAccount(AccountType.BUYER, "alice", "", credentialService))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("密码");
    }

    /**
     * 创建账号-店铺关联：账号行与店铺行各自生成 ID，两端 ID 正确。
     */
    @Test
    void createAccountShopRel_initializesRel() {
        final AccountShopRel rel = factory.createAccountShopRel(1001L, 2001L);
        assertThat(rel.getId()).isNotNull();
        assertThat(rel.getAccountId()).isEqualTo(1001L);
        assertThat(rel.getShopId()).isEqualTo(2001L);
    }
}
