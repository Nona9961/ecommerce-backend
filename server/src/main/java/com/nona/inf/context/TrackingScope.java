package com.nona.inf.context;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每作用域可变的跟踪上下文持有者（per-scope holder，非 Spring bean）。
 * <p>
 * 由 {@link TrackingContext#withScope(Runnable)} 创建并作为 {@link ScopedValue} 的值绑定，
 * 绑定期间引用稳定：作用域内对字段的写入为单线程操作（JEP 506「不可变或同步」之「或」分支）。
 * 持有者<b>永不跨线程共享</b>——跨线程边界一律以不可变快照传播。
 * <p>
 * 承载内容：
 * <ul>
 *   <li>三元组（tenantID / role / identity）：消费者授权过滤器的迁移写入目标
 *       （原 {@code threadContext.setX} 路径改为本持有者 setter，须运行在 {@link TrackingContext#withScope} 作用域内）</li>
 *   <li>{@code snapshots}：根对象注册表（{@code DifferRepository.isTracked} 读、快照登记写）</li>
 *   <li>{@code tracker}：懒创建——首次 {@link #getOrCreateTracker} 时才创建并留存，
 *       非 DB 访问路径永不创建；异步 worker 场景（SNAPSHOT 槽携带非空
 *       {@code trackingBaseline}）从基线重建（{@code ChangeTracker.fromBaseline}，不重脱水），
 *       其余路径维持 {@code provider.create()} 懒语义</li>
 * </ul>
 *
 * @author nona9961
 */
@ScaffoldGenerated
public final class TrackingScope {

    /**
     * 当前租户 ID
     */
    private String tenantID;

    /**
     * 当前角色列表（多角色支持）
     */
    private List<String> role;

    /**
     * 请求者身份标识（token / userId / apiKey 等）
     */
    private String identity;

    /**
     * 根对象快照注册表（key 为根对象 ID）。
     * <p>
     * 经 {@link #getSnapshots()} 原地读写（put / containsKey 等），不允许整体重绑。
     */
    private final Map<Long, Object> snapshots = new ConcurrentHashMap<>(8);

    /**
     * 懒创建的变更追踪器：首次 {@link #getOrCreateTracker} 时创建并在本作用域内留存；
     * 未触发追踪的请求保持 {@code null}（零开销）。
     */
    private volatile ChangeTracker tracker;

    /**
     * 获取当前租户 ID。
     *
     * @return 租户 ID；未写入时为 {@code null}
     */
    public String getTenantID() {
        return tenantID;
    }

    /**
     * 设置当前租户 ID。
     *
     * @param tenantID 租户 ID；可更新为 {@code null} 表示清除
     */
    public void setTenantID(String tenantID) {
        this.tenantID = tenantID;
    }

    /**
     * 获取当前角色列表。
     *
     * @return 角色列表；未写入时为 {@code null}
     */
    public List<String> getRole() {
        return role;
    }

    /**
     * 设置当前角色列表。
     *
     * @param role 角色列表；可更新为 {@code null} 表示清除
     */
    public void setRole(List<String> role) {
        this.role = role;
    }

    /**
     * 获取请求者身份标识。
     *
     * @return 身份标识；未写入时为 {@code null}
     */
    public String getIdentity() {
        return identity;
    }

    /**
     * 设置请求者身份标识。
     *
     * @param identity 身份标识；可更新为 {@code null} 表示清除
     */
    public void setIdentity(String identity) {
        this.identity = identity;
    }

    /**
     * 获取根对象快照注册表（返回内部集合，允许原地读写）。
     *
     * @return 根对象 ID → 根对象 的注册表
     */
    public Map<Long, Object> getSnapshots() {
        return snapshots;
    }

    /**
     * 获取当前作用域的变更追踪器；尚未创建时懒创建并留存。
     * <p>
     * <strong>首次创建钩子</strong>：创建时刻检查 SNAPSHOT 槽已绑定快照
     * （{@link TenantContextAccessor#boundSnapshot()}）——快照携带非空
     * {@code trackingBaseline}（异步 worker：提交线程已导出基线）时经
     * {@code ChangeTracker.fromBaseline(provider.createCapability(), baseline)} 从基线重建，
     * <b>不重新脱水</b>（对已修改实体重新 track 会得到空 diff，变更静默丢失）；
     * 否则维持 {@code provider.create()}（懒语义不变）。基线不存在是合法态（纯读传播）。
     * <p>
     * 幂等：同一作用域内重复调用返回同一实例，提供者创建路径至多执行一次；
     * 非 DB 访问路径不调用本方法，则永不创建追踪器。
     *
     * @param provider ChangeTracker 创建提供者；不得为 {@code null}
     * @return 当前作用域持有的 ChangeTracker 实例（懒创建）
     */
    public ChangeTracker getOrCreateTracker(ChangeTrackerProvider provider) {
        Objects.requireNonNull(provider, "provider cannot be null");
        if (tracker == null) {
            synchronized (this) {
                if (tracker == null) {
                    tracker = createTrackerFromBoundBaselineOrPlain(provider);
                }
            }
        }
        return tracker;
    }

    /**
     * 查询当前作用域是否已创建追踪器（无副作用，不触发创建）。
     * <p>
     * 供快照捕获路径（{@link TenantContextAccessor#captureSnapshot()}）使用：
     * <b>仅当</b>追踪器已存在时才允许导出基线（{@code captureBaseline()} 深拷贝），
     * 保证「非 DB 请求永不创建追踪器」的懒语义不被捕获动作破坏。
     *
     * @return 已创建的追踪器；尚未创建时返回 {@code null}
     */
    @Nullable
    public ChangeTracker trackerIfPresent() {
        return tracker;
    }

    /**
     * 首次创建路径：SNAPSHOT 槽携带非空基线时从基线重建，否则走提供者普通创建。
     *
     * @param provider ChangeTracker 创建提供者；不得为 {@code null}
     * @return 新创建的 ChangeTracker 实例
     */
    private ChangeTracker createTrackerFromBoundBaselineOrPlain(ChangeTrackerProvider provider) {
        final TenantContextAccessor.ContextSnapshot bound = TenantContextAccessor.boundSnapshot();
        if (bound != null && bound.trackingBaseline() != null) {
            return ChangeTracker.fromBaseline(provider.createCapability(), bound.trackingBaseline());
        }
        return provider.create();
    }
}