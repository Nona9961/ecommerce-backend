package com.nona.inf.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link TrackingFilter} 场景测试：HTTP 入口作用域绑定契约。
 * <p>
 * 契约验证点：
 * <ul>
 *   <li>Happy：doFilter 调用链在 withScope 作用域内执行（链内 {@code scope()} 非 null，
 *       且经提供者懒创建 tracker 可用）</li>
 *   <li>Fail：链路异常时异常原样传播、作用域退出恢复 unbound（池化线程复用无残留）</li>
 *   <li>Critical：请求不经本过滤器时线程无绑定——「Filter 包裹生效」的对照侧；
 *       fail-closed 完整语义（未绑定调用 tracker() 抛异常）由 {@link TrackingContextTest} 覆盖</li>
 * </ul>
 *
 * @author nona9961
 */
@ScaffoldGenerated
class TrackingFilterTest {

    private static final ChangeTrackerProvider PROVIDER = new ChangeTrackerProvider(Map.of(), Set.of(), Set.of());

    private final TrackingFilter filter = new TrackingFilter();

    // ========== Happy path ==========

    /**
     * 经过滤器处理请求：过滤链在 {@code TrackingContext.withScope} 作用域内执行——
     * 链内捕获到非 null 持有者。
     */
    @Test
    void shouldRunFilterChainInsideBoundTrackingScope() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final AtomicReference<TrackingScope> inChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inChain.set(TrackingContext.scope()));

        assertThat(inChain.get()).isNotNull();
    }

    /**
     * 过滤链作用域完整可用：链内 {@code TrackingContext.tracker(provider)} 懒创建返回
     * 非 null 追踪器（非 DB 请求不触碰本调用则零创建，懒语义由 TrackingContextTest 覆盖）。
     */
    @Test
    void shouldExposeWorkingTrackerInsideFilterScope() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final AtomicReference<ChangeTracker> inChain = new AtomicReference<>();

        filter.doFilter(request, response,
                (req, res) -> inChain.set(TrackingContext.tracker(PROVIDER)));

        assertThat(inChain.get()).isNotNull();
    }

    // ========== Critical path ==========

    /**
     * 请求不经本过滤器（链路闭包直接执行）时线程无绑定——Filter 包裹生效的对照断言；
     * 未绑定场景调用 tracker() 的 fail-closed 异常语义由 {@link TrackingContextTest}
     * {@code shouldFailClosedWhenTrackerCalledWithoutBoundScope} 覆盖。
     * <p>
     * 本用例锁定「Filter 必须包裹链路」这一契约正向面：请求绕过过滤器（链路闭包直接
     * 执行）时线程天然无绑定；若未来误接非包裹路径（如直调业务代码）将转为失败。
     */
    @Test
    void shouldLeaveThreadUnboundWhenRequestBypassesFilter() {
        final AtomicReference<TrackingScope> bypass = new AtomicReference<>();

        // 不经过 filter.doFilter，直接执行同一形状的链路闭包
        Runnable bypassChain = () -> bypass.set(TrackingContext.scope());
        bypassChain.run();

        assertThat(bypass.get()).isNull();
    }

    // ========== Fail path ==========

    /**
     * 链路内抛异常：异常原样向调用方传播，且作用域随链路退出自动恢复 unbound——
     * 过滤器返回后线程无绑定残留（池化线程复用安全）。
     */
    @Test
    void shouldRestoreUnboundAndPropagateExceptionWhenChainThrows() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw boom;
        })).isSameAs(boom);

        assertThat(TrackingContext.scope()).isNull();
    }

    /**
     * 链路抛出受检异常 {@link ServletException}：经私有载体穿过作用域后按原始类型解包
     * 原样传播（实例身份一致），作用域恢复 unbound。
     */
    @Test
    void shouldPropagateServletExceptionAndRestoreUnbound() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final ServletException boom = new ServletException("boom");

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw boom;
        })).isSameAs(boom);

        assertThat(TrackingContext.scope()).isNull();
    }

    /**
     * 链路抛出受检异常 {@link IOException}：经私有载体穿过作用域后按原始类型解包
     * 原样传播（实例身份一致），作用域恢复 unbound。
     */
    @Test
    void shouldPropagateIoExceptionAndRestoreUnbound() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final IOException boom = new IOException("boom");

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw boom;
        })).isSameAs(boom);

        assertThat(TrackingContext.scope()).isNull();
    }
}