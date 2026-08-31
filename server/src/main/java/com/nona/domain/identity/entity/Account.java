package com.nona.domain.identity.entity;

/**
 * 账号聚合根：买家与商家的统一账号主体（对应单表 {@code account}，type 列区分角色）。
 * <p>
 * 领域模型 M5 冻结定义：Account 聚合只有一个，内容 = credentials（username/password hash）
 * + status + type（buyer/merchant）；Account stays lean——地址/收藏/店铺/入驻均为独立聚合
 * （AddressBook/Favorite/Shop/OnboardingApplication），不存在需要分表的字段差异。
 * <p>
 * 不变量：同 type 内用户名唯一（持久化层 (type, username) 联合唯一约束）；密码只以 BCrypt
 * 摘要形式进入聚合（由 {@link com.nona.domain.identity.factory.AccountFactory} 经凭证端口
 * 加密后拼装）；创建必须经由 Factory + {@link com.nona.util.IDUtils#generateID()}。
 *
 * @author nona9961
 */
public class Account {

    /**
     * 账号 ID（Snowflake）
     */
    private final Long id;

    /**
     * 用户名（同 type 命名空间内唯一）
     */
    private final String username;

    /**
     * 密码 BCrypt 摘要
     */
    private final String passwordHash;

    /**
     * 账号状态
     */
    private AccountStatus status;

    /**
     * 账号类型（BUYER / SELLER，创建即定不可变）
     */
    private final AccountType type;

    /**
     * 构造账号（仅 Factory 调用）。
     *
     * @param id           账号 ID
     * @param username     用户名
     * @param passwordHash 密码 BCrypt 摘要
     * @param status       初始状态
     * @param type         账号类型
     */
    public Account(Long id, String username, String passwordHash, AccountStatus status, AccountType type) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.status = status;
        this.type = type;
    }

    /**
     * 账号 ID。
     *
     * @return 账号 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 用户名。
     *
     * @return 用户名
     */
    public String getUsername() {
        return username;
    }

    /**
     * 密码 BCrypt 摘要。
     *
     * @return 密码摘要
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * 账号状态。
     *
     * @return 账号状态
     */
    public AccountStatus getStatus() {
        return status;
    }

    /**
     * 账号类型。
     *
     * @return 账号类型
     */
    public AccountType getType() {
        return type;
    }

    /**
     * 更新账号状态（封禁/解封）。
     *
     * @param status 新状态
     */
    public void changeStatus(AccountStatus status) {
        this.status = status;
    }
}
