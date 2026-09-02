package com.nona.inf.persistence.po.identity;

import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 角色持久化对象（role 表，global）：平台侧角色体系中的单个角色。
 * <p>
 * 角色与租户无关（不随店铺隔离），编码在体系内唯一
 * （uk_role_code）；商家角色与店铺员工角色属后续版本的完整模型扩展，
 * 本表不预埋租户列。
 *
 * @author nona9961
 */
@Entity
@Table(name = "role", uniqueConstraints = {
        @UniqueConstraint(name = "uk_role_code", columnNames = {"code"})
})
public class RolePO extends BasePO {

    /**
     * 角色编码（体系内唯一，如 PLATFORM_ADMIN）
     */
    @Column(nullable = false, length = 64)
    private String code;

    /**
     * 角色名称（展示用，如 平台管理员）
     */
    @Column(nullable = false, length = 64)
    private String name;

    /**
     * 角色编码。
     *
     * @return 角色编码
     */
    public String getCode() {
        return code;
    }

    /**
     * 设置角色编码。
     *
     * @param code 角色编码
     */
    public void setCode(String code) {
        this.code = code;
    }

    /**
     * 角色名称。
     *
     * @return 角色名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置角色名称。
     *
     * @param name 角色名称
     */
    public void setName(String name) {
        this.name = name;
    }
}
