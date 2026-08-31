package com.nona.inf.persistence.po.identity;

import com.nona.domain.identity.entity.AccountStatus;
import com.nona.domain.identity.entity.AccountType;
import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 账号持久化对象（单表 {@code account}，global）：买家/商家统一承载，
 * type 列区分角色（BUYER/SELLER），(type, username) 联合唯一。
 * <p>
 * 领域模型 M5：Account 聚合只有一个，持久化即一张表——不存在需要分表的字段差异
 * （地址/收藏/店铺/入驻均为独立聚合）；平台运营（admin）账号不在本表承载
 * （RBAC 落点在 WU-10/Phase-II，portal=ADMIN 查询定向不到即拒绝，fail-closed）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "account", uniqueConstraints = {
        @UniqueConstraint(name = "uk_account_type_username", columnNames = {"type", "username"})
})
public class AccountPO extends BasePO {

    /**
     * 账号类型（BUYER / SELLER）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountType type;

    /**
     * 用户名（同 type 命名空间内唯一）
     */
    @Column(nullable = false, length = 64)
    private String username;

    /**
     * 密码 BCrypt 摘要
     */
    @Column(nullable = false, length = 64, name = "password_hash")
    private String passwordHash;

    /**
     * 账号状态（NORMAL/BANNED）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    /**
     * 账号类型。
     *
     * @return 账号类型
     */
    public AccountType getType() {
        return type;
    }

    /**
     * 设置账号类型。
     *
     * @param type 账号类型
     */
    public void setType(AccountType type) {
        this.type = type;
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
     * 设置用户名。
     *
     * @param username 用户名
     */
    public void setUsername(String username) {
        this.username = username;
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
     * 设置密码摘要。
     *
     * @param passwordHash 密码摘要
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
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
     * 设置账号状态。
     *
     * @param status 账号状态
     */
    public void setStatus(AccountStatus status) {
        this.status = status;
    }
}
