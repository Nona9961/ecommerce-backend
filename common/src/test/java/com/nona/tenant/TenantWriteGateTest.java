package com.nona.tenant;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户写门禁纯函数单测（common 模块首个测试类，零 Spring 上下文）。
 * <p>
 * 覆盖 4 判定分支全表 + 哨兵加固 + 空白归一化三组 + null 边界（设计 §5.2，
 * 审查 blocker-1 测试落点）。判定语义：{@code 提权状态 × 实体归属}，与操作方法名无关。
 *
 * @author nona9961
 */
class TenantWriteGateTest {

    // ---- 提权分支（elevated = true）----

    @Test
    @DisplayName("提权 + null 归属 → 拒绝")
    void elevatedWithNullTenantShouldReject() {
        assertThatThrownBy(() -> TenantWriteGate.decideInjection(null, "t1", true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("elevated write requires explicit tenantId");
    }

    @Test
    @DisplayName("提权 + 空白归属 → 拒绝（归一化收口）")
    void elevatedWithBlankTenantShouldReject() {
        assertThatThrownBy(() -> TenantWriteGate.decideInjection("   ", "t1", true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("elevated write requires explicit tenantId");
    }

    @Test
    @DisplayName("哨兵 提权 + MISSING 归属 → 拒绝")
    void elevatedWithMissingSentinelShouldReject() {
        assertThatThrownBy(() -> TenantWriteGate.decideInjection(TenantWriteGate.MISSING_TENANT_ID, "t1", true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid tenantId cannot be used as entity tenant")
                .hasMessageContaining(TenantWriteGate.MISSING_TENANT_ID);
    }

    @Test
    @DisplayName("哨兵 提权 + ROOT 归属 → 拒绝")
    void elevatedWithRootSentinelShouldReject() {
        assertThatThrownBy(() -> TenantWriteGate.decideInjection(TenantWriteGate.ROOT_TENANT_ID, "t1", true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid tenantId cannot be used as entity tenant")
                .hasMessageContaining(TenantWriteGate.ROOT_TENANT_ID);
    }

    @Test
    @DisplayName("提权 + 显式合法归属 → 放行（null 返回、不注入）")
    void elevatedWithExplicitTenantShouldPass() {
        assertThat(TenantWriteGate.decideInjection("t2", "t1", true)).isNull();
    }

    // ---- 非提权分支（elevated = false）----

    @Test
    @DisplayName("非提权 + context null → 拒绝")
    void nonElevatedWithNullContextShouldReject() {
        assertThatThrownBy(() -> TenantWriteGate.decideInjection("t1", null, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tenantID is required for tenant-scoped write");
    }

    @Test
    @DisplayName("非提权 + null 归属 → 注入 contextTenant")
    void nonElevatedWithNullTenantShouldInjectContext() {
        assertThat(TenantWriteGate.decideInjection(null, "t1", false)).isEqualTo("t1");
    }

    @Test
    @DisplayName("非提权 + 空白归属 → 注入 contextTenant（归一化收口）")
    void nonElevatedWithBlankTenantShouldInjectContext() {
        assertThat(TenantWriteGate.decideInjection("   ", "t1", false)).isEqualTo("t1");
    }

    @Test
    @DisplayName("非提权 + 归属一致 → 放行")
    void nonElevatedWithConsistentTenantShouldPass() {
        assertThat(TenantWriteGate.decideInjection("t1", "t1", false)).isNull();
    }

    @Test
    @DisplayName("非提权 + 归属不一致 → 拒绝")
    void nonElevatedWithMismatchedTenantShouldReject() {
        assertThatThrownBy(() -> TenantWriteGate.decideInjection("t2", "t1", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cross-tenant write is forbidden")
                .hasMessageContaining("t1")
                .hasMessageContaining("t2");
    }

    @Test
    @DisplayName("哨兵 非提权 + MISSING 归属 → 拒绝")
    void nonElevatedWithMissingSentinelShouldReject() {
        assertThatThrownBy(() -> TenantWriteGate.decideInjection(TenantWriteGate.MISSING_TENANT_ID, "t1", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid tenantId cannot be used as entity tenant");
    }

    @Test
    @DisplayName("哨兵 非提权 + ROOT 归属 → 拒绝")
    void nonElevatedWithRootSentinelShouldReject() {
        assertThatThrownBy(() -> TenantWriteGate.decideInjection(TenantWriteGate.ROOT_TENANT_ID, "t1", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid tenantId cannot be used as entity tenant");
    }

    @Test
    @DisplayName("非提权 + context null + 归属 null → 拒绝（视角缺失优先于注入）")
    void nonElevatedBothMissingShouldReject() {
        assertThatThrownBy(() -> TenantWriteGate.decideInjection(null, null, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tenantID is required for tenant-scoped write");
    }

    @Test
    @DisplayName("哨兵权重于一致性：非提权 + MISSING vs 一致性豁免保证拒绝（防污染）")
    void sentinelWinsOverConsistencyInBothBranches() {
        // MISSING 归属即使与（非法）context 相同也不放行——哨兵前置在一致性判定之前
        assertThatThrownBy(() -> TenantWriteGate.decideInjection(TenantWriteGate.MISSING_TENANT_ID, "t1", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid tenantId cannot be used as entity tenant");
        assertThatThrownBy(() -> TenantWriteGate.decideInjection(TenantWriteGate.ROOT_TENANT_ID, "t1", true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid tenantId cannot be used as entity tenant");
    }
}
