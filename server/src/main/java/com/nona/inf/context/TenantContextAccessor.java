package com.nona.inf.context;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.tenant.TenantWriteGate;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;

/**
 * 租户上下文读取器（基于 {@link ThreadContext}）。
 *
 * @author nona9961
 */
@Component
@RequiredArgsConstructor
@ScaffoldGenerated
public class TenantContextAccessor {

    /**
     * tenant 缺失时使用的占位值，用于 fail-closed（不放行 tenant-scoped 数据）。
     * <p>
     * 转发常量：权威定义在 common 的 {@link TenantWriteGate#MISSING_TENANT_ID}（规则与常量统一上移 common，此处转发以兼容既有调用）。
     * 保留本转发位以兼容现有引用（getTenantIDOrMissing 与测试）。
     */
    public static final String MISSING_TENANT_ID = TenantWriteGate.MISSING_TENANT_ID;

    /**
     * 异步线程回退存储：worker 线程经 {@link #saveSnapshot} 写入
     */
    private static final ThreadLocal<ContextSnapshot> snapshotHolder = new ThreadLocal<>();

    /**
     * 请求作用域 ThreadContext 的懒加载提供者
     */
    private final ObjectProvider<ThreadContext> threadContextProvider;

    /**
     * Saves a {@link ContextSnapshot} into the static {@link ThreadLocal} fallback store.
     * <p>
     * Worker threads should call this at the start of async execution so that
     * {@link #getTenantID()} resolves via the fallback when no request scope is active.
     *
     * @param snapshot the snapshot to store; {@code null} or {@link ContextSnapshot#EMPTY} clears
     */
    public static void saveSnapshot(@Nullable ContextSnapshot snapshot) {
        if (snapshot == null || snapshot == ContextSnapshot.EMPTY) {
            snapshotHolder.remove();
            return;
        }
        snapshotHolder.set(snapshot);
    }

    /**
     * Clears the static {@link ThreadLocal} fallback. Call from {@code finally} blocks in worker
     * threads to prevent pollution.
     */
    public static void clearSnapshot() {
        snapshotHolder.remove();
    }

    /**
     * Captures a snapshot of the current {@link ThreadContext} for cross-thread propagation.
     * <p>
     * Only propagates: tenantID, role, identity. Other fields (attributes, snapshots) are not
     * captured.
     *
     * @return a snapshot of the current context, or a snapshot with all-null fields if
     *         {@link ThreadContext} is not in scope
     */
    public ContextSnapshot captureSnapshot() {
        final ThreadContext threadContext = getThreadContextIfActive();
        if (threadContext == null) {
            return ContextSnapshot.EMPTY;
        }
        return new ContextSnapshot(
                threadContext.getTenantID(),
                threadContext.getRole(),
                threadContext.getIdentity()
        );
    }

    /**
     * 获取当前请求上下文中的 tenantID。
     * <p>
     * Resolution order:
     * <ol>
     *   <li>{@link ThreadContext} (request-scoped bean) — when request scope is active</li>
     *   <li>ThreadLocal fallback (set via {@link #saveSnapshot(ContextSnapshot)}) —
     *       for async/worker threads</li>
     * </ol>
     *
     * @return tenantID；若当前未处于 {@link ThreadContext} 的 request scope，或 tenantID 缺失/为空白则返回 {@code null}
     */
    @Nullable
    public String getTenantID() {
        final ThreadContext threadContext = getThreadContextIfActive();
        if (threadContext != null) {
            final String tenantID = threadContext.getTenantID();
            if (tenantID != null && !tenantID.isBlank()) {
                return tenantID;
            }
        }

        final ContextSnapshot snapshot = snapshotHolder.get();
        if (snapshot != null) {
            final String tenantID = snapshot.tenantID();
            if (tenantID != null && !tenantID.isBlank()) {
                return tenantID;
            }
        }
        return null;
    }

    /**
     * 获取当前请求上下文中的 tenantID；tenant 缺失时返回占位值。
     *
     * @return tenantID；若 tenant 缺失则返回 {@link #MISSING_TENANT_ID}
     */
    public String getTenantIDOrMissing() {
        final String tenantID = getTenantID();
        if (tenantID == null) {
            return MISSING_TENANT_ID;
        }
        return tenantID;
    }

    /**
     * 获取当前请求上下文中的 role 列表。
     * <p>
     * Resolution order: 1) request-scoped {@link ThreadContext} → 2) ThreadLocal fallback.
     *
     * @return role 列表；若缺失则返回 {@code null}
     */
    @Nullable
    public List<String> getRole() {
        final ThreadContext threadContext = getThreadContextIfActive();
        if (threadContext != null) {
            final List<String> role = threadContext.getRole();
            if (role != null) {
                return role;
            }
        }

        final ContextSnapshot snapshot = snapshotHolder.get();
        if (snapshot != null) {
            return snapshot.role();
        }
        return null;
    }

    /**
     * 获取当前请求上下文中的 identity。
     * <p>
     * Resolution order: 1) request-scoped {@link ThreadContext} → 2) ThreadLocal fallback.
     *
     * @return identity；若缺失则返回 {@code null}
     */
    @Nullable
    public String getIdentity() {
        final ThreadContext threadContext = getThreadContextIfActive();
        if (threadContext != null) {
            final String identity = threadContext.getIdentity();
            if (identity != null) {
                return identity;
            }
        }

        final ContextSnapshot snapshot = snapshotHolder.get();
        if (snapshot != null) {
            return snapshot.identity();
        }
        return null;
    }

    /**
     * 在 request scope 激活时获取 {@link ThreadContext}；否则返回 {@code null}。
     *
     * @return 当前 ThreadContext；若 request scope 未激活则返回 {@code null}
     */
    @Nullable
    ThreadContext getThreadContextIfActive() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        return threadContextProvider.getIfAvailable();
    }

    /**
     * Immutable snapshot of cross-cutting request context for async thread propagation.
     * <p>
     * Only carries the subset of {@link ThreadContext} fields that are safe and necessary
     * for async execution: tenantID, role, and identity. Attributes and root snapshots are
     * intentionally excluded because they may carry request-scoped mutable state.
     *
     * @param tenantID 租户 ID；可能为 null
     * @param role     角色列表；可能为 null
     * @param identity 请求者身份；可能为 null
     */
    public record ContextSnapshot(
            @Nullable String tenantID,
            @Nullable List<String> role,
            @Nullable String identity
    ) {
        /** Sentinel snapshot representing an absent / cleared context. */
        public static final ContextSnapshot EMPTY = new ContextSnapshot(null, null, null);
    }
}
