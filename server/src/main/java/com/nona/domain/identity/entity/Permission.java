package com.nona.domain.identity.entity;

/**
 * 权限点（RBAC 模型，对应 permission 表，global）：平台侧系统定义的单个权限点。
 * <p>
 * 权限点为系统定义数据（如入驻审核、商品审核等操作），由平台侧维护，与租户无关；
 * 细粒度鉴权在后续版本接入。角色与账号-角色分配见 {@link Role} 与 {@link Assignment}。
 * 权限点创建后 code 在体系内唯一（数据库唯一约束兜底）。
 *
 * @author nona9961
 */
public class Permission {

    /**
     * 权限点 ID（Snowflake）
     */
    private final Long id;

    /**
     * 权限点编码（体系内唯一，如 onboarding.review）
     */
    private final String code;

    /**
     * 权限点名称（展示用，如 入驻审核）
     */
    private final String name;

    /**
     * 构造权限点。
     *
     * @param id   权限点 ID
     * @param code 权限点编码
     * @param name 权限点名称
     */
    public Permission(Long id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
    }

    /**
     * 权限点 ID。
     *
     * @return 权限点 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 权限点编码。
     *
     * @return 权限点编码
     */
    public String getCode() {
        return code;
    }

    /**
     * 权限点名称。
     *
     * @return 权限点名称
     */
    public String getName() {
        return name;
    }
}
