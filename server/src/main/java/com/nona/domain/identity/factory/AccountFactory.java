package com.nona.domain.identity.factory;

import com.nona.domain.identity.entity.Account;
import com.nona.domain.identity.entity.AccountShopRel;
import com.nona.domain.identity.entity.AccountStatus;
import com.nona.domain.identity.entity.AccountType;
import com.nona.domain.identity.ports.CredentialService;
import com.nona.util.BusinessAssert;
import com.nona.util.IDUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 账号聚合根工厂：负责账号聚合的创建与不变量初始化。
 * <p>
 * 领域模型 M5：Account 聚合只有一个（买家/商家是 type 值，不是两类结构），
 * 故工厂只暴露按 type 创建的单一入口 {@link #createAccount}；
 * 平台运营（admin）账号无落点（RBAC 问题属 WU-10/Phase-II），不在本工厂范围。
 * <p>
 * 密码不变量在此保证：明文密码只经 {@link CredentialService#hash} 转为 BCrypt 摘要后
 * 才进入聚合（凭证端口由基础设施实现，创建路径始终完整可用）。
 *
 * @author nona9961
 */
@Component
public class AccountFactory {

    /**
     * 创建账号（买家或商家，按 type 区分；账号-店铺关联由入驻审核通过时另行绑定）。
     *
     * @param type              账号类型（BUYER / SELLER）
     * @param username          用户名（同 type 命名空间内唯一）
     * @param rawPassword       明文密码
     * @param credentialService 凭证端口（BCrypt 加密）
     * @return 新建的账号
     */
    public Account createAccount(AccountType type, String username, String rawPassword,
                                 CredentialService credentialService) {
        BusinessAssert.assertNonNull(type, "账号类型不能为空");
        assertCredentials(username, rawPassword);
        final String passwordHash = credentialService.hash(rawPassword);
        return new Account(IDUtils.generateID(), username, passwordHash, AccountStatus.NORMAL, type);
    }

    /**
     * 创建账号-店铺关联（M10）。
     *
     * @param accountId 账号 ID
     * @param shopId    店铺 ID
     * @return 新建的关联
     */
    public AccountShopRel createAccountShopRel(Long accountId, Long shopId) {
        BusinessAssert.assertNonNull(accountId, "账号 ID 不能为空");
        BusinessAssert.assertNonNull(shopId, "店铺 ID 不能为空");
        return new AccountShopRel(IDUtils.generateID(), accountId, shopId);
    }

    /**
     * 校验注册凭证形态。
     *
     * @param username    用户名
     * @param rawPassword 明文密码
     */
    private static void assertCredentials(String username, String rawPassword) {
        BusinessAssert.assertTrue(StringUtils.isNotBlank(username), "用户名不能为空");
        BusinessAssert.assertTrue(StringUtils.isNotBlank(rawPassword), "密码不能为空");
    }
}
