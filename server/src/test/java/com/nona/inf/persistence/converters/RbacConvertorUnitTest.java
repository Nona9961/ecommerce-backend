package com.nona.inf.persistence.converters;

import com.nona.domain.identity.entity.Assignment;
import com.nona.domain.identity.entity.Permission;
import com.nona.domain.identity.entity.Role;
import com.nona.inf.persistence.po.identity.AssignmentPO;
import com.nona.inf.persistence.po.identity.PermissionPO;
import com.nona.inf.persistence.po.identity.RolePO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RBAC 三表转换器单元测试：角色 / 权限点 / 账号-角色分配实体 ↔ PO 字段映射往返一致。
 *
 * @author nona9961
 */
class RbacConvertorUnitTest {

    /**
     * 角色转换器
     */
    private final RoleConvertor roleConvertor = new RoleConvertor();

    /**
     * 权限点转换器
     */
    private final PermissionConvertor permissionConvertor = new PermissionConvertor();

    /**
     * 账号-角色分配转换器
     */
    private final AssignmentConvertor assignmentConvertor = new AssignmentConvertor();

    /**
     * happy：角色实体 → PO → 实体往返字段一致。
     */
    @Test
    @DisplayName("角色往返转换字段一致")
    void role_roundTrip_keepsAllFields() {
        final Role source = new Role(90001L, "PLATFORM_ADMIN", "平台管理员");

        final RolePO po = roleConvertor.convertToPO(source);
        assertThat(po.getId()).isEqualTo(90001L);
        assertThat(po.getCode()).isEqualTo("PLATFORM_ADMIN");
        assertThat(po.getName()).isEqualTo("平台管理员");

        final Role back = roleConvertor.convertToRoot(po, null);
        assertThat(back.getId()).isEqualTo(source.getId());
        assertThat(back.getCode()).isEqualTo(source.getCode());
        assertThat(back.getName()).isEqualTo(source.getName());
    }

    /**
     * happy：权限点实体 → PO → 实体往返字段一致。
     */
    @Test
    @DisplayName("权限点往返转换字段一致")
    void permission_roundTrip_keepsAllFields() {
        final Permission source = new Permission(90002L, "onboarding.review", "入驻审核");

        final PermissionPO po = permissionConvertor.convertToPO(source);
        assertThat(po.getId()).isEqualTo(90002L);
        assertThat(po.getCode()).isEqualTo("onboarding.review");
        assertThat(po.getName()).isEqualTo("入驻审核");

        final Permission back = permissionConvertor.convertToRoot(po, null);
        assertThat(back.getId()).isEqualTo(source.getId());
        assertThat(back.getCode()).isEqualTo(source.getCode());
        assertThat(back.getName()).isEqualTo(source.getName());
    }

    /**
     * happy：账号-角色分配实体 → PO → 实体往返字段一致。
     */
    @Test
    @DisplayName("账号-角色分配往返转换字段一致")
    void assignment_roundTrip_keepsAllFields() {
        final Assignment source = new Assignment(90003L, 1001L, 90001L);

        final AssignmentPO po = assignmentConvertor.convertToPO(source);
        assertThat(po.getId()).isEqualTo(90003L);
        assertThat(po.getAccountId()).isEqualTo(1001L);
        assertThat(po.getRoleId()).isEqualTo(90001L);

        final Assignment back = assignmentConvertor.convertToRoot(po, null);
        assertThat(back.getId()).isEqualTo(source.getId());
        assertThat(back.getAccountId()).isEqualTo(source.getAccountId());
        assertThat(back.getRoleId()).isEqualTo(source.getRoleId());
    }

    /**
     * critical：null 输入安全（转换器无业务逻辑，null 直通返回 null）。
     */
    @Test
    @DisplayName("空输入安全处理")
    void nullInput_safe() {
        assertThat(roleConvertor.convertToPO(null)).isNull();
        assertThat(roleConvertor.convertToRoot(null, null)).isNull();
        assertThat(permissionConvertor.convertToPO(null)).isNull();
        assertThat(permissionConvertor.convertToRoot(null, null)).isNull();
        assertThat(assignmentConvertor.convertToPO(null)).isNull();
        assertThat(assignmentConvertor.convertToRoot(null, null)).isNull();
    }
}
