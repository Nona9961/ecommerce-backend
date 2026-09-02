package com.nona.inf.persistence.po.identity;

import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 权限点持久化对象（permission 表，global）：平台侧系统定义的单个权限点。
 * <p>
 * 权限点为系统定义数据（如入驻审核、商品审核等操作），由平台侧维护，
 * 与租户无关；编码在体系内唯一（uk_permission_code）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "permission", uniqueConstraints = {
        @UniqueConstraint(name = "uk_permission_code", columnNames = {"code"})
})
public class PermissionPO extends BasePO {

    /**
     * 权限点编码（体系内唯一，如 onboarding.review）
     */
    @Column(nullable = false, length = 128)
    private String code;

    /**
     * 权限点名称（展示用，如 入驻审核）
     */
    @Column(nullable = false, length = 64)
    private String name;

    /**
     * 权限点编码。
     *
     * @return 权限点编码
     */
    public String getCode() {
        return code;
    }

    /**
     * 设置权限点编码。
     *
     * @param code 权限点编码
     */
    public void setCode(String code) {
        this.code = code;
    }

    /**
     * 权限点名称。
     *
     * @return 权限点名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置权限点名称。
     *
     * @param name 权限点名称
     */
    public void setName(String name) {
        this.name = name;
    }
}
