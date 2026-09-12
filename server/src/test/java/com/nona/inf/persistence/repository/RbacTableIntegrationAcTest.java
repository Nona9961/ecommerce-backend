package com.nona.inf.persistence.repository;

import com.nona.inf.persistence.po.identity.AssignmentPO;
import com.nona.inf.persistence.po.identity.PermissionPO;
import com.nona.inf.persistence.po.identity.RolePO;
import com.nona.inf.persistence.repository.jpa.AssignmentJpaRepository;
import com.nona.inf.persistence.repository.jpa.PermissionJpaRepository;
import com.nona.inf.persistence.repository.jpa.RoleJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RBAC 三表（角色 / 权限点 / 账号-角色分配）集成测试：建表存在性与可写读验证。
 * <p>
 * 三表均为平台全局数据（不随租户隔离）：角色 = 平台角色体系；权限点 = 平台侧
 * 系统定义；分配 = 账号-角色分配。本期仅建表与模型就位，管理界面与细粒度
 * 鉴权在后续版本接入，故本测试直用 JPA 仓储验证「表存在 + 可写读 + 唯一约束」。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class RbacTableIntegrationAcTest {

    /**
     * 角色表 JPA 仓储（写读验证）
     */
    @Autowired
    private RoleJpaRepository roleRepository;

    /**
     * 权限点表 JPA 仓储（写读验证）
     */
    @Autowired
    private PermissionJpaRepository permissionRepository;

    /**
     * 分配表 JPA 仓储（写读验证）
     */
    @Autowired
    private AssignmentJpaRepository assignmentRepository;

    /**
     * JDBC 模板（information_schema 表存在断言）
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 每用例前清空三表。
     */
    @BeforeEach
    void setUp() {
        assignmentRepository.deleteAll();
        permissionRepository.deleteAll();
        roleRepository.deleteAll();
    }

    /**
     * 表存在：role / permission / assignment 三张表均已由 Hibernate 建出。
     * 查询按当前连接库适配（H2-ism 吸收）：H2 默认 schema 为 PUBLIC，
     * MySQL 为库名 —— 以 DATABASE() 取当前库，两态兼容。
     */
    @Test
    @DisplayName("RBAC 三表已建出")
    void tables_areCreated() {
        final List<String> tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                String.class);

        assertThat(tables.stream().map(String::toUpperCase).toList())
                .contains("ROLE", "PERMISSION", "ASSIGNMENT");
    }

    /**
     * 可写读：角色表插入后可整体读回（编码/名称一致）。
     */
    @Test
    @DisplayName("角色表可写读")
    void role_saveAndReadBack() {
        roleRepository.save(newRolePo(1L, "PLATFORM_ADMIN", "平台运营"));

        final Optional<RolePO> loaded = roleRepository.findById(1L);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getCode()).isEqualTo("PLATFORM_ADMIN");
        assertThat(loaded.get().getName()).isEqualTo("平台运营");
    }

    /**
     * 可写读：权限点表插入后可整体读回（编码/名称一致）。
     */
    @Test
    @DisplayName("权限点表可写读")
    void permission_saveAndReadBack() {
        permissionRepository.save(newPermissionPo(2L, "onboarding.review", "入驻审核"));

        final Optional<PermissionPO> loaded = permissionRepository.findById(2L);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getCode()).isEqualTo("onboarding.review");
        assertThat(loaded.get().getName()).isEqualTo("入驻审核");
    }

    /**
     * 可写读：账号-角色分配表插入后可整体读回（账号/角色 ID 一致）。
     */
    @Test
    @DisplayName("账号-角色分配表可写读")
    void assignment_saveAndReadBack() {
        assignmentRepository.save(newAssignmentPo(3L, 10001L, 1L));

        final Optional<AssignmentPO> loaded = assignmentRepository.findById(3L);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getAccountId()).isEqualTo(10001L);
        assertThat(loaded.get().getRoleId()).isEqualTo(1L);
    }

    /**
     * 唯一约束：角色编码重复插入被拒绝（同编码角色至多一行）。
     */
    @Test
    @DisplayName("角色编码唯一约束拒绝重复")
    void role_duplicateCode_rejectsByUniqueConstraint() {
        roleRepository.save(newRolePo(1L, "PLATFORM_ADMIN", "平台运营"));

        assertThatThrownBy(() -> roleRepository.saveAndFlush(newRolePo(2L, "PLATFORM_ADMIN", "平台操作员")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(roleRepository.count()).isEqualTo(1);
    }

    /**
     * 唯一约束：权限点编码重复插入被拒绝（同编码权限点至多一行）。
     */
    @Test
    @DisplayName("权限点编码唯一约束拒绝重复")
    void permission_duplicateCode_rejectsByUniqueConstraint() {
        permissionRepository.save(newPermissionPo(2L, "onboarding.review", "入驻审核"));

        assertThatThrownBy(() -> permissionRepository.saveAndFlush(newPermissionPo(3L, "onboarding.review", "审核入驻")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(permissionRepository.count()).isEqualTo(1);
    }

    /**
     * 唯一约束：同一账号同一角色重复分配被拒绝（每账号每角色至多一行）。
     */
    @Test
    @DisplayName("账号-角色分配唯一约束拒绝重复")
    void assignment_duplicatePair_rejectsByUniqueConstraint() {
        assignmentRepository.save(newAssignmentPo(3L, 10001L, 1L));

        assertThatThrownBy(() -> assignmentRepository.saveAndFlush(newAssignmentPo(4L, 10001L, 1L)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(assignmentRepository.count()).isEqualTo(1);
    }

    /**
     * 构造角色测试行。
     *
     * @param id   主键
     * @param code 角色编码
     * @param name 角色名称
     * @return 角色 PO
     */
    private static RolePO newRolePo(Long id, String code, String name) {
        final RolePO po = new RolePO();
        po.setId(id);
        po.setCode(code);
        po.setName(name);
        return po;
    }

    /**
     * 构造权限点测试行。
     *
     * @param id   主键
     * @param code 权限点编码
     * @param name 权限点名称
     * @return 权限点 PO
     */
    private static PermissionPO newPermissionPo(Long id, String code, String name) {
        final PermissionPO po = new PermissionPO();
        po.setId(id);
        po.setCode(code);
        po.setName(name);
        return po;
    }

    /**
     * 构造账号-角色分配测试行。
     *
     * @param id        主键
     * @param accountId 账号 ID
     * @param roleId    角色 ID
     * @return 分配 PO
     */
    private static AssignmentPO newAssignmentPo(Long id, Long accountId, Long roleId) {
        final AssignmentPO po = new AssignmentPO();
        po.setId(id);
        po.setAccountId(accountId);
        po.setRoleId(roleId);
        return po;
    }
}
