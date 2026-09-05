package com.nona.inf.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.nona.annotation.ScaffoldGenerated;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 场景契约测试：{@link TenantContextAccessor} 读取面单级化
 * （request scope 两级解析 → {@link TrackingContext} holder 单级主通路）。
 * <p>
 * 契约：{@code getTenantID} / {@code getRole} / {@code getIdentity} / {@code captureSnapshot}
 * 解析顺序收敛为：
 * <ol>
 *   <li>{@link TrackingContext#scope()} 持有者优先（字段非空才取）</li>
 *   <li>{@code boundSnapshot()} 嵌套异步回退（worker 内再派发继承外层视角；
 *       真实传播形状 = {@code withSnapshot(snapshot, () -> withScope(task))}）</li>
 *   <li>两者皆无可取 → {@code null}（fail-closed 保留）</li>
 * </ol>
 * request scope bean 不再参与读取；holder 优先类断言为单级化契约的落地锁，纯回退 /
 * fail-closed 类断言为回归锁（单级化不得破坏既有语义）。
 *
 * @author nona9961
 */
@ScaffoldGenerated
class TenantAccessorSingleLevelTest {

    /**
     * 被测访问器：单级化后无参构造（字段删除，默认无参）；本测试始终不激活 request
     * scope（纯 JUnit 形态，直接实例化，不经 Spring 装配）。
     */
    private final TenantContextAccessor accessor = new TenantContextAccessor();

    // ==================== Happy path ====================

    /**
     * H1（主通路）：withScope 内写入 holder 三元组 → 读取经 holder（单级第一顺位）。
     */
    @Test
    void holderTripletShouldBeReadWithinScope() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("t1");
            TrackingContext.scope().setRole(List.of("admin"));
            TrackingContext.scope().setIdentity("user-42");

            assertThat(accessor.getTenantID()).isEqualTo("t1");
            assertThat(accessor.getRole()).containsExactly("admin");
            assertThat(accessor.getIdentity()).isEqualTo("user-42");
        });
    }

    /**
     * H2（嵌套异步回退）：holder 未写 + boundSnapshot 携带三元组
     * （{@code withSnapshot} 包裹 {@code withScope} 的真实 worker 形状）→ 回退生效；
     * 回归锁：单级化不得破坏既有回退语义。
     */
    @Test
    void boundSnapshotShouldFallbackWhenHolderEmpty() {
        final TenantContextAccessor.ContextSnapshot snapshot = new TenantContextAccessor.ContextSnapshot(
                "fallback-tenant", List.of("visitor"), "fb-user");
        TenantContextAccessor.withSnapshot(snapshot, () -> TrackingContext.withScope(() -> {
            assertThat(accessor.getTenantID()).isEqualTo("fallback-tenant");
            assertThat(accessor.getRole()).containsExactly("visitor");
            assertThat(accessor.getIdentity()).isEqualTo("fb-user");
        }));
    }

    /**
     * H3（提交视角捕获）：captureSnapshot 单级化——holder 三元组为捕获来源
     * （异步提交线程经 holder 写入后的跨线程传播视角）。
     */
    @Test
    void captureSnapshotShouldReadFromHolder() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("t1");
            TrackingContext.scope().setRole(List.of("admin"));
            TrackingContext.scope().setIdentity("user-42");

            final TenantContextAccessor.ContextSnapshot captured = accessor.captureSnapshot();
            assertThat(captured.tenantID()).isEqualTo("t1");
            assertThat(captured.role()).containsExactly("admin");
            assertThat(captured.identity()).isEqualTo("user-42");
        });
    }

    // ==================== Critical path ====================

    /**
     * C1（fail-closed）：无 scope 无 snapshot → 三元组读取全 {@code null}
     * （单级收敛后 fail-closed 语义保留）。
     */
    @Test
    void unboundThreadShouldFailClosedToNull() {
        assertThat(accessor.getTenantID()).isNull();
        assertThat(accessor.getRole()).isNull();
        assertThat(accessor.getIdentity()).isNull();
    }

    /**
     * C2（占位）：tenant 缺失时 {@code getTenantIDOrMissing} 返回 MISSING 占位
     * （fail-closed 占位语义保留，消费方 resolver / adapter 依赖）。
     */
    @Test
    void missingTenantShouldYieldPlaceholder() {
        assertThat(accessor.getTenantIDOrMissing())
                .isEqualTo(TenantContextAccessor.MISSING_TENANT_ID);
    }

    // ==================== Fail path ====================

    /**
     * F1（unbound 恢复）：退出 withScope 后 holder 写入不可见——词法作用域自动清理，
     * 池化线程复用无残留（JEP 506 语义）；出口回落断言为回归锁。
     */
    @Test
    void holderWriteShouldNotLeakAfterScopeExit() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("t1");
            assertThat(accessor.getTenantID()).isEqualTo("t1");
        });
        assertThat(accessor.getTenantID()).isNull();
    }

    /**
     * F2（嵌套 holder 覆盖外层快照）：{@code withSnapshot(outer)} 包裹
     * {@code withScope(inner)}，inner holder 写入优先于外层 boundSnapshot
     * （嵌套语义：scope() 取最近者，holder 非空字段优先）。
     */
    @Test
    void innerScopeHolderShouldOverrideOuterBoundSnapshot() {
        final TenantContextAccessor.ContextSnapshot outer = new TenantContextAccessor.ContextSnapshot(
                "outer-tenant", List.of("admin"), "outer-user");
        TenantContextAccessor.withSnapshot(outer, () -> TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("inner-tenant");

            assertThat(accessor.getTenantID()).isEqualTo("inner-tenant");
        }));
    }

    /**
     * F3（字段级解析）：holder 已写字段优先，未写字段逐字段回退 boundSnapshot——
     * 单级解析按字段独立取源，不做「整体要么 holder 要么快照」的粗粒度切换。
     */
    @Test
    void holderFilledFieldShouldOverrideSnapshotPerField() {
        final TenantContextAccessor.ContextSnapshot snapshot = new TenantContextAccessor.ContextSnapshot(
                "snap-tenant", List.of("admin"), "snap-user");
        TenantContextAccessor.withSnapshot(snapshot, () -> TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("holder-tenant");

            assertThat(accessor.getTenantID()).isEqualTo("holder-tenant");
            assertThat(accessor.getRole()).containsExactly("admin");
            assertThat(accessor.getIdentity()).isEqualTo("snap-user");
        }));
    }
}
