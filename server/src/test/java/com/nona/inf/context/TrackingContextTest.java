package com.nona.inf.context;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TrackingContext} 场景测试：词法作用域绑定 / 懒创建 / fail-closed。
 * <p>
 * 契约验证点：
 * <ul>
 *   <li>Happy：withScope 内 scope() 与 tracker() 各自幂等；未触碰追踪时提供者零调用</li>
 *   <li>Critical：未绑定作用域调用 tracker() 抛 {@link IllegalStateException}（fail-closed）</li>
 *   <li>Fail：作用域退出（含异常路径）自动 unbound；嵌套作用域退出恢复外层</li>
 * </ul>
 *
 * @author nona9961
 */
@ScaffoldGenerated
class TrackingContextTest {

    // ========== Happy path ==========

    /**
     * withScope 作用域内两次 scope() 返回同一持有者实例（引用稳定）。
     */
    @Test
    void shouldReturnSameScopeInstanceWithinScope() {
        final AtomicReference<TrackingScope> first = new AtomicReference<>();
        final AtomicReference<TrackingScope> second = new AtomicReference<>();

        TrackingContext.withScope(() -> {
            first.set(TrackingContext.scope());
            second.set(TrackingContext.scope());
        });

        assertThat(first.get()).isNotNull();
        assertThat(second.get()).isSameAs(first.get());
    }

    /**
     * withScope 作用域内两次 tracker() 返回同一实例（懒创建留存），且 provider.create()
     * 只被调用一次。
     */
    @Test
    void shouldReturnSameTrackerWithinScope() {
        final CountingProvider provider = new CountingProvider();
        final AtomicReference<ChangeTracker> first = new AtomicReference<>();
        final AtomicReference<ChangeTracker> second = new AtomicReference<>();

        TrackingContext.withScope(() -> {
            first.set(TrackingContext.tracker(provider));
            second.set(TrackingContext.tracker(provider));
        });

        assertThat(first.get()).isNotNull();
        assertThat(second.get()).isSameAs(first.get());
        assertThat(provider.creations()).isEqualTo(1);
    }

    /**
     * 不触碰 tracker 的作用域：provider.create() 零调用（懒创建，非 DB 访问零追踪开销）。
     */
    @Test
    void shouldNotCreateTrackerWhenTrackerUntouched() {
        final CountingProvider provider = new CountingProvider();

        TrackingContext.withScope(() -> assertThat(TrackingContext.scope()).isNotNull());

        assertThat(provider.creations()).isZero();
    }

    // ========== Critical path ==========

    /**
     * 未绑定作用域（无入口组件包裹）调用 tracker()：fail-closed 抛
     * {@link IllegalStateException}，而非静默降级。
     */
    @Test
    void shouldFailClosedWhenTrackerCalledWithoutBoundScope() {
        assertThatThrownBy(() -> TrackingContext.tracker(new CountingProvider()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ========== Fail path ==========

    /**
     * 作用域正常退出后：scope() 恢复为 null，tracker() 恢复抛异常（词法作用域自动恢复）。
     */
    @Test
    void shouldBeUnboundAfterScopeExit() {
        TrackingContext.withScope(() -> assertThat(TrackingContext.scope()).isNotNull());

        assertThat(TrackingContext.scope()).isNull();
        assertThatThrownBy(() -> TrackingContext.tracker(new CountingProvider()))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 作用域内抛异常退出后：异常向调用方传播，scope() 恢复为 null（异常路径自动恢复，
     * 池化线程复用无残留）。
     */
    @Test
    void shouldRestoreUnboundAfterExceptionInScope() {
        assertThatThrownBy(() -> TrackingContext.withScope(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TrackingContext.scope()).isNull();
    }

    /**
     * 嵌套 withScope：内层作用域退出后恢复外层持有者；内外持有者非同一实例。
     */
    @Test
    void shouldRestoreOuterScopeAfterNestedScopeExits() {
        final AtomicReference<TrackingScope> outer = new AtomicReference<>();
        final AtomicReference<TrackingScope> inner = new AtomicReference<>();

        TrackingContext.withScope(() -> {
            outer.set(TrackingContext.scope());
            TrackingContext.withScope(() -> inner.set(TrackingContext.scope()));
            assertThat(TrackingContext.scope()).isSameAs(outer.get());
        });

        assertThat(inner.get()).isNotNull();
        assertThat(inner.get()).isNotSameAs(outer.get());
    }

    // ========== 测试用提供者 ==========

    /**
     * 计数 ChangeTrackerProvider：统计 create() 调用次数（懒创建断言）。
     */
    static final class CountingProvider extends ChangeTrackerProvider {

        private int creations;

        CountingProvider() {
            super(Map.of(), Set.of(), Set.of());
        }

        int creations() {
            return creations;
        }

        @Override
        public ChangeTracker create() {
            creations++;
            return super.create();
        }
    }
}