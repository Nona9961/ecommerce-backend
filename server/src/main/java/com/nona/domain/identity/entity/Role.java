package com.nona.domain.identity.entity;

/**
 * 角色（RBAC 模型，对应 role 表，global）：平台侧角色体系中的单个角色。
 * <p>
 * 角色为平台运营维度定义的职能集合（如平台管理员、平台运营），与租户无关
 * （不随店铺隔离）；商家角色与店铺员工角色属后续版本的完整模型扩展，本期
 * 不预埋租户列。权限点与账号-角色分配见 {@link Permission} 与 {@link Assignment}。
 * 角色创建后 code 在体系内唯一（数据库唯一约束兜底）。
 *
 * @author nona9961
 */
public class Role {

    /**
     * 角色 ID（Snowflake）
     */
    private final Long id;

    /**
     * 角色编码（体系内唯一，如 PLATFORM_ADMIN）
     */
    private final String code;

    /**
     * 角色名称（展示用，如 平台管理员）
     */
    private final String name;

    /**
     * 构造角色。
     *
     * @param id   角色 ID
     * @param code 角色编码
     * @param name 角色名称
     */
    public Role(Long id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
    }

    /**
     * 角色 ID。
     *
     * @return 角色 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 角色编码。
     *
     * @return 角色编码
     */
    public String getCode() {
        return code;
    }

    /**
     * 角色名称。
     *
     * @return 角色名称
     */
    public String getName() {
        return name;
    }
}
