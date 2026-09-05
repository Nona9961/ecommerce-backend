package com.nona.inf.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nona.EcommerceApplication;
import com.nona.annotation.ScaffoldGenerated;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import com.nona.changeTracking.domain.model.tracking.BaselineSnapshot;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.inf.context.TenantContextAccessor.ContextSnapshot;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskDecorator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 异步传播升级场景测试——以「升级后契约」编写：提交线程捕获三元组 +
 * {@code trackingBaseline}（{@code tracker.captureBaseline()} 深拷贝），worker 侧
 * 经 {@code TenantContextAccessor.withSnapshot(snapshot, () ->
 * TrackingContext.withScope(task))} 嵌套绑定后，首次 {@code tracker()} 从基线重建
 * （{@code ChangeTracker.fromBaseline(provider.createCapability(), baseline)}——
 * 不重脱水）。所需契约面（{@code ContextSnapshot.trackingBaseline()} 访问器与
 * {@link BaselineSnapshot}）由库 jar 提供，本测试直接锁定升级后契约。
 * <p>
 * 契约验证点：
 * <ul>
 *   <li>Happy：读 → 改 → 异步提交 → worker 追踪器非主线程实例、{@code calculateChanges()}
 *       产出完整变更集（基线 = 读时状态，worker 不重脱水——重脱水对已修改实体为空 diff）</li>
 *   <li>Critical：纯读任务（提交线程无 tracker）→ 快照 {@code trackingBaseline} 为 null，
 *       worker {@code tracker()} 直接 {@code provider.create()} 不炸</li>
 *   <li>Fail：池化线程复用无残留；异常路径自动恢复；export 后提交线程继续 track
 *       另一实体不污染已导出基线（深拷贝隔离）</li>
 * </ul>
 *
 * @author nona9961
 */
@SpringBootTest(classes = EcommerceApplication.class)
@ScaffoldGenerated
class AsyncTrackingPropagationTest {

    @Autowired
    private TenantContextAccessor tenantContextAccessor;

    /**
     * 无配置提供者：走 SPI 默认能力（default-reflection）。
     *
     * @return 测试用 ChangeTrackerProvider 实例
     */
    private static ChangeTrackerProvider newProvider() {
        return new ChangeTrackerProvider(Map.of(), Set.of(), Set.of());
    }

    // ========== Happy path ==========

    /**
     * 读 → 改 → 异步保存主链路：提交线程 withScope 内建 tracker + track 实体 + 业务修改，
     * 经（升级后）装饰器提交；worker 内 scope 嵌套绑定生效、tracker() 非空且与主线程
     * tracker 不同实例，calculateChanges() 产出完整变更集（基线 = 读时状态，
     * worker 不重脱水——重脱水对已修改实体产生空 diff，变更静默丢失）。
     */
    @Test
    void shouldRebuildWorkerTrackerFromCapturedBaselineAndProduceFullChangeSet() throws Exception {
        final ChangeTrackerProvider provider = newProvider();
        final TaskDecorator decorator = new RequestContextPropagatingTaskDecorator(tenantContextAccessor);
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TrackingContext.withScope(() -> {
                final ChangeTracker submitterTracker = TrackingContext.tracker(provider);
                final TrackedEntity entity = new TrackedEntity(1L, "PENDING");
                submitterTracker.track(entity);                // 登记基线（读时状态）
                entity.setStatus("CONFIRMED");                 // 业务修改（异步提交前）

                // 提交时刻快照：三元组 + 基线（深拷贝导出）一并随任务传播
                final ContextSnapshot captured = tenantContextAccessor.captureSnapshot();
                final BaselineSnapshot baseline = captured.trackingBaseline();
                assertThat(baseline).isNotNull();

                final AtomicReference<ChangeSet> workerChanges = new AtomicReference<>();
                final Runnable task = () -> {
                    assertThat(TrackingContext.scope()).isNotNull();          // 嵌套越 scope 生效
                    final ChangeTracker workerTracker = TrackingContext.tracker(provider);
                    assertThat(workerTracker).isNotSameAs(submitterTracker);  // 独立实例重建
                    workerChanges.set(workerTracker.calculateChanges());
                };
                try {
                    pool.submit(decorator.decorate(task)).get(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("async task interrupted", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("async task failed", e);
                } catch (TimeoutException e) {
                    throw new IllegalStateException("async task timed out after 5s", e);
                }

                // worker 完整变更集：基线 = 读时状态（PENDING），diff 出 CONFIRMED
                final ChangeSet changeSet = workerChanges.get();
                assertThat(changeSet).isNotNull();
                assertThat(changeSet.isEmpty()).isFalse();
                final List<ValueChange> valueChanges = changeSet.getLeafChanges().stream()
                        .filter(ValueChange.class::isInstance)
                        .map(ValueChange.class::cast)
                        .toList();
                assertThat(valueChanges).anySatisfy(c -> {
                    assertThat(c.fieldName()).isEqualTo("status");
                    assertThat(c.oldValue()).isEqualTo("PENDING");
                    assertThat(c.newValue()).isEqualTo("CONFIRMED");
                });
            });
        } finally {
            pool.shutdownNow();
        }
    }

    // ========== Critical path ==========

    /**
     * 嵌套派发（worker 内再派发）继承外层视角：worker1 从外层基线重建 tracker 后继续
     * track 新实体并修改，再捕获快照——三元组回退继承外层快照，基线为 worker1 当前
     * 深拷贝（含两层实体）——worker2 从该基线重建，变更集涵盖外层与嵌套实体。
     */
    @Test
    void nestedDispatchShouldInheritOuterSnapshotAndCurrentBaseline() throws Exception {
        final ChangeTrackerProvider provider = newProvider();
        final TaskDecorator decorator = new RequestContextPropagatingTaskDecorator(tenantContextAccessor);
        final ExecutorService outerPool = Executors.newSingleThreadExecutor();
        final ExecutorService innerPool = Executors.newSingleThreadExecutor();
        try {
            TrackingContext.withScope(() -> {
                final ChangeTracker outerTracker = TrackingContext.tracker(provider);
                final TrackedEntity entityA = new TrackedEntity(1L, "PENDING");
                outerTracker.track(entityA);
                entityA.setStatus("CONFIRMED");

                final AtomicReference<List<ValueChange>> nestedChanges = new AtomicReference<>();
                try {
                    outerPool.submit(decorator.decorate(() -> {
                        final ChangeTracker workerTracker = TrackingContext.tracker(provider);
                        final TrackedEntity entityB = new TrackedEntity(2L, "NEW");
                        workerTracker.track(entityB);
                        entityB.setStatus("ACTIVE");

                        final Runnable innerTask = () -> {
                            final ChangeTracker worker2Tracker = TrackingContext.tracker(provider);
                            nestedChanges.set(worker2Tracker.calculateChanges().getLeafChanges().stream()
                                    .filter(ValueChange.class::isInstance)
                                    .map(ValueChange.class::cast)
                                    .toList());
                        };
                        try {
                            innerPool.submit(decorator.decorate(innerTask)).get(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("nested async task interrupted", e);
                        } catch (ExecutionException e) {
                            throw new IllegalStateException("nested async task failed", e);
                        } catch (TimeoutException e) {
                            throw new IllegalStateException("nested async task timed out after 5s", e);
                        }
                    })).get(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("async task interrupted", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("async task failed", e);
                } catch (TimeoutException e) {
                    throw new IllegalStateException("async task timed out after 5s", e);
                }

                final List<ValueChange> changes = nestedChanges.get();
                assertThat(changes).isNotNull();
                assertThat(changes).anySatisfy(c -> {
                    assertThat(c.fieldName()).isEqualTo("status");
                    assertThat(c.oldValue()).isEqualTo("PENDING");
                    assertThat(c.newValue()).isEqualTo("CONFIRMED");
                });
                assertThat(changes).anySatisfy(c -> {
                    assertThat(c.fieldName()).isEqualTo("status");
                    assertThat(c.oldValue()).isEqualTo("NEW");
                    assertThat(c.newValue()).isEqualTo("ACTIVE");
                });
            });
        } finally {
            outerPool.shutdownNow();
            innerPool.shutdownNow();
        }
    }

    /**
     * 纯读任务（提交线程从未调用 tracker()）：快照 trackingBaseline 为 null——
     * worker 侧 tracker() 直接 provider.create()（懒语义不变），不抛异常、无基线即空变更集。
     */
    @Test
    void shouldKeepBaselineNullAndCreatePlainTrackerForReadOnlyTask() throws Exception {
        final ChangeTrackerProvider provider = newProvider();
        final TaskDecorator decorator = new RequestContextPropagatingTaskDecorator(tenantContextAccessor);
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TrackingContext.withScope(() -> {
                // 纯读：不触碰 tracker()——捕获快照 baseline 为 null
                final ContextSnapshot captured = tenantContextAccessor.captureSnapshot();
                final BaselineSnapshot baseline = captured.trackingBaseline();
                assertThat(baseline).isNull();

                try {
                    pool.submit(decorator.decorate(() -> {
                        assertThat(TrackingContext.scope()).isNotNull();
                        final ChangeTracker workerTracker = TrackingContext.tracker(provider);
                        assertThat(workerTracker.calculateChanges().isEmpty()).isTrue();
                    })).get(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("async task interrupted", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("async task failed", e);
                } catch (TimeoutException e) {
                    throw new IllegalStateException("async task timed out after 5s", e);
                }
            });
        } finally {
            pool.shutdownNow();
        }
    }

    // ========== Fail path ==========

    /**
     * 池化线程复用无残留：带绑定任务执行后，同一池化线程裸任务读不到 scope 绑定
     * （worker 侧两槽 withSnapshot + withScope 均随作用域退出自动 unbound）。
     */
    @Test
    void pooledThreadReuseShouldNotLeakWorkerScopeBinding() throws Exception {
        final ChangeTrackerProvider provider = newProvider();
        final TaskDecorator decorator = new RequestContextPropagatingTaskDecorator(tenantContextAccessor);
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TrackingContext.withScope(() -> {
                TrackingContext.tracker(provider);             // 提交线程建立 tracker（导出路径）
                try {
                    pool.submit(decorator.decorate(() ->
                            assertThat(TrackingContext.scope()).isNotNull())).get(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("async task interrupted", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("async task failed", e);
                } catch (TimeoutException e) {
                    throw new IllegalStateException("async task timed out after 5s", e);
                }
            });

            // 同一池化线程、未经装饰的裸任务：无 scope 残留
            final AtomicReference<TrackingScope> residue = new AtomicReference<>();
            pool.submit(() -> residue.set(TrackingContext.scope())).get(5, TimeUnit.SECONDS);
            assertThat(residue.get()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 异常路径自动恢复：worker 任务内抛异常后，同一池化线程裸任务读不到残留绑定；
     * 异常经 Future 传播为 ExecutionException（cause 为链内异常）。
     */
    @Test
    void exceptionPathShouldAutoRestoreWorkerScopeBinding() throws Exception {
        final ChangeTrackerProvider provider = newProvider();
        final TaskDecorator decorator = new RequestContextPropagatingTaskDecorator(tenantContextAccessor);
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TrackingContext.withScope(() -> {
                TrackingContext.tracker(provider);
                assertThatThrownBy(() -> pool.submit(decorator.decorate(() -> {
                    assertThat(TrackingContext.scope()).isNotNull();
                    throw new IllegalStateException("boom");
                })).get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class);
            });

            final AtomicReference<TrackingScope> residue = new AtomicReference<>();
            pool.submit(() -> residue.set(TrackingContext.scope())).get(5, TimeUnit.SECONDS);
            assertThat(residue.get()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 深拷贝隔离：装饰器捕获快照（导出基线）之后，提交线程继续 track 另一实体——
     * 不影响已导出基线，worker 计算不受污染（仅比较导出时已登记的实体；
     * worker 侧基线实体键为目标实体实例本身——IdentityHashMap 键语义）。
     */
    @Test
    void baselineDeepCopyShouldIsolateWorkerFromSubmitterLaterTracking() throws Exception {
        final ChangeTrackerProvider provider = newProvider();
        final TaskDecorator decorator = new RequestContextPropagatingTaskDecorator(tenantContextAccessor);
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TrackingContext.withScope(() -> {
                final ChangeTracker submitterTracker = TrackingContext.tracker(provider);
                final TrackedEntity entityA = new TrackedEntity(1L, "PENDING");
                submitterTracker.track(entityA);
                entityA.setStatus("CONFIRMED");                // 业务修改

                // 装饰器在提交线程捕获：此刻基线仅含 entityA（深拷贝导出）
                final Runnable decorated = decorator.decorate(() -> {
                    final ChangeTracker workerTracker = TrackingContext.tracker(provider);
                    final ChangeSet changeSet = workerTracker.calculateChanges();
                    assertThat(changeSet.changes()).hasSize(1);              // 仅 entityA
                    assertThat(changeSet.changes().get(0).target()).isSameAs(entityA);
                });

                // 导出之后提交线程继续追踪另一实体——不污染已导出基线
                final TrackedEntity entityB = new TrackedEntity(2L, "NEW");
                submitterTracker.track(entityB);

                try {
                    pool.submit(decorated).get(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("async task interrupted", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("async task failed", e);
                } catch (TimeoutException e) {
                    throw new IllegalStateException("async task timed out after 5s", e);
                }
            });
        } finally {
            pool.shutdownNow();
        }
    }

    // ========== 测试域模型 ==========

    /**
     * 测试用可变实体：私有字段 + setter，默认反射能力（ValueNodeSnapshotStrategy
     * 反射读字段，setAccessible）即可快照；仅含原始类型字段（无集合/自定义类型干扰）。
     */
    static final class TrackedEntity {

        private long id;

        private String status;

        TrackedEntity(long id, String status) {
            this.id = id;
            this.status = status;
        }

        void setStatus(String status) {
            this.status = status;
        }
    }
}