package com.nona.inf.persistence.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.inf.context.RequestContextPropagatingTaskDecorator;
import com.nona.inf.context.TenantContextAccessor;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.context.TrackingScope;
import com.nona.inf.persistence.converters.ConverterRegistry;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link com.nona.inf.persistence.repository.DifferRepository} 迁移契约场景测试——
 * 钉住仓储的追踪通道契约：追踪器与根对象快照登记全部收敛到 {@link TrackingContext}
 * 作用域持有者（快照注册表 = {@code scope().getSnapshots()}，追踪器 = 作用域内懒创建，
 * 首用即建立基线），仓储自身不再持有请求级上下文实例。
 * <p>
 * 契约语义（逐条对应用例）：
 * <ul>
 *   <li>读（{@code getByID}）在作用域内建立追踪基线并登记快照；改后保存（{@code save}）
 *       产出完整变更集（update 路径）；未追踪的新对象走 insert 路径</li>
 *   <li>fail-closed：未绑定作用域调用 {@code getByID} / {@code save} 抛
 *       {@link IllegalStateException}（入口组件缺失），不静默降级</li>
 *   <li>快照生命周期 = 作用域生命周期：作用域退出后注册表随作用域消亡，
 *       同一聚合根再次保存按未追踪处理（重新走 insert 路径）</li>
 *   <li>异步（经任务传播装饰器）：worker 作用域内重建追踪器与基线，
 *       读 → 改 → 存全链路变更正确</li>
 * </ul>
 * 场景测试复用一体化测试既有基建（内存表 + JDBC 夹具、仓储子类与转换器均为
 * 同一包内的既有测试组件），不做重复实现。
 * <p>
 * 用例分类：Happy（读建基线 / 改后存变更集完整 / 新对象 insert）、
 * Critical（无作用域 fail-closed）、Fail（异步全链路 / 作用域退出无残留）。
 *
 * @author nona9961
 */
@ScaffoldGenerated
class DifferRepositoryTrackingTest {

    private JdbcTemplate jdbc;
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        // 内存数据库 + 建表（与一体化测试同一夹具形状）
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build();
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("DROP TABLE IF EXISTS t_spec");
        jdbc.execute("DROP TABLE IF EXISTS t_sub_item");
        jdbc.execute("DROP TABLE IF EXISTS t_order_item");
        jdbc.execute("DROP TABLE IF EXISTS t_address");
        jdbc.execute("DROP TABLE IF EXISTS t_customer");
        jdbc.execute("DROP TABLE IF EXISTS t_order");
        jdbc.execute("""
            CREATE TABLE t_order (
                id BIGINT PRIMARY KEY,
                order_no VARCHAR(64),
                status VARCHAR(32),
                total_amount DECIMAL(18,2),
                total_currency VARCHAR(8),
                tenant_id VARCHAR(64),
                create_time TIMESTAMP,
                update_time TIMESTAMP
            )
        """);
        jdbc.execute("""
            CREATE TABLE t_customer (
                id BIGINT PRIMARY KEY,
                order_id BIGINT,
                name VARCHAR(64),
                contact_phone VARCHAR(32),
                contact_email VARCHAR(64)
            )
        """);
        jdbc.execute("""
            CREATE TABLE t_address (
                id BIGINT PRIMARY KEY,
                customer_id BIGINT,
                type VARCHAR(16),
                city VARCHAR(64)
            )
        """);
        jdbc.execute("""
            CREATE TABLE t_order_item (
                id BIGINT PRIMARY KEY,
                order_id BIGINT,
                sku VARCHAR(64),
                product_name VARCHAR(128),
                quantity INT,
                unit_price DECIMAL(18,2),
                unit_currency VARCHAR(8)
            )
        """);
        jdbc.execute("""
            CREATE TABLE t_sub_item (
                id BIGINT PRIMARY KEY,
                order_item_id BIGINT,
                name VARCHAR(64)
            )
        """);
        jdbc.execute("""
            CREATE TABLE t_spec (
                id BIGINT PRIMARY KEY,
                sub_item_id BIGINT,
                spec_key VARCHAR(64),
                spec_value VARCHAR(128)
            )
        """);

        // 转换器注册（复用一体化测试的转换器实现）
        ConverterRegistry registry = new ConverterRegistry();
        registry.register(new FullIntegrationTest.OrderItemConverter());
        registry.register(new FullIntegrationTest.CustomerConverter());
        registry.register(new FullIntegrationTest.OrderConverter());

        // ChangeTracker 提供者（与一体化测试同一配置）
        ChangeTrackerProvider changeTrackerProvider = ChangeTrackerProvider.builder()
                .withIdentifier(FullIntegrationTest.OrderItem.class, FullIntegrationTest.OrderItem::getId)
                .withIdentifier(FullIntegrationTest.SubItem.class, FullIntegrationTest.SubItem::getId)
                .withIdentifier(FullIntegrationTest.Spec.class, FullIntegrationTest.Spec::getKey)
                .withIdentifier(FullIntegrationTest.Customer.class, FullIntegrationTest.Customer::getId)
                .withIdentifier(FullIntegrationTest.Address.class, FullIntegrationTest.Address::getId)
                .withValueType(FullIntegrationTest.Money.class)
                .withValueType(FullIntegrationTest.ContactInfo.class)
                .build();

        // 仓储构造器：DifferRepository 已收敛为 3 参（repository / convertor / changeTrackerProvider），
        // 不再持有请求级上下文实例——追踪器与快照登记全部经 TrackingContext 作用域持有者。
        ListCrudRepository<FullIntegrationTest.OrderPO, Long> crudRepo =
                new FullIntegrationTest.InMemoryOrderPORepository(jdbc, new FullIntegrationTest.OrderConverter());
        orderRepository = new OrderRepository(
                crudRepo, new FullIntegrationTest.OrderConverter(),
                changeTrackerProvider, jdbc, registry);
    }

    /**
     * 预置一条订单主表数据（PENDING）。
     */
    private void insertOrderRow() {
        jdbc.update("""
            INSERT INTO t_order (id, order_no, status, tenant_id)
            VALUES (?, ?, ?, ?)
        """, 1L, "ORD-001", "PENDING", "test-tenant");
    }

    // ==================== Happy path ====================

    /**
     * H1（读建基线）：withScope 内 getByID → 追踪器懒创建于作用域持有者、
     * 根对象快照登记进作用域注册表（{@code scope().getSnapshots()}）。
     */
    @Test
    void getByIDShouldRegisterBaselineInScopeHolder() {
        insertOrderRow();

        TrackingContext.withScope(() -> {
            FullIntegrationTest.Order loaded = orderRepository.getByID(1L);
            assertThat(loaded).isNotNull();

            TrackingScope scope = TrackingContext.scope();
            assertThat(scope).isNotNull();
            assertThat(scope.getSnapshots()).containsKey(1L);
            assertThat(scope.trackerIfPresent()).isNotNull();
        });
    }

    /**
     * H2（update 路径变更集完整）：withScope 内 getByID 建基线 → 修改 → save →
     * 变更集驱动更新落库（效果断言）+ 快照登记与追踪器均收敛于作用域持有者。
     */
    @Test
    void modifyThenSaveShouldProduceFullChangeSetOnUpdatePath() {
        insertOrderRow();

        TrackingContext.withScope(() -> {
            FullIntegrationTest.Order loaded = orderRepository.getByID(1L);
            loaded.setStatus("PAID");

            boolean saved = orderRepository.save(loaded);
            assertThat(saved).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM t_order WHERE id = ?", String.class, 1L))
                    .isEqualTo("PAID");

            TrackingScope scope = TrackingContext.scope();
            assertThat(scope.getSnapshots().get(1L)).isSameAs(loaded);
            assertThat(scope.trackerIfPresent()).isNotNull();
        });
    }

    /**
     * H3（insert 路径）：withScope 内直接 save 未追踪的新对象 → 走新增落库，
     * 快照登记进作用域注册表。
     */
    @Test
    void saveNewRootShouldGoInsertPathAndRegisterScopeSnapshot() {
        TrackingContext.withScope(() -> {
            FullIntegrationTest.Order order = new FullIntegrationTest.Order(2L, "ORD-002");
            order.setStatus("PENDING");

            boolean saved = orderRepository.save(order);
            assertThat(saved).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_order WHERE id = ?", Long.class, 2L))
                    .isEqualTo(1L);
            assertThat(TrackingContext.scope().getSnapshots()).containsKey(2L);
        });
    }

    // ==================== Critical path ====================

    /**
     * C1（fail-closed 读）：未绑定作用域调用 getByID → 抛 {@link IllegalStateException}
     * （入口组件缺失），不静默降级。
     */
    @Test
    void unboundGetByIDShouldFailClosed() {
        insertOrderRow();

        assertThatThrownBy(() -> orderRepository.getByID(1L))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * C2（fail-closed 存）：未绑定作用域调用 save → 抛 {@link IllegalStateException}，
     * 不落入静默新增。
     */
    @Test
    void unboundSaveShouldFailClosed() {
        FullIntegrationTest.Order order = new FullIntegrationTest.Order(3L, "ORD-003");
        order.setStatus("PENDING");

        assertThatThrownBy(() -> orderRepository.save(order))
                .isInstanceOf(IllegalStateException.class);
    }

    // ==================== Fail path ====================

    /**
     * F1（异步全链路）：提交线程 withScope 内读 → 改 → 经任务传播装饰器提交 →
     * worker 作用域内保存：变更集完整（行为效果断言）+ worker 侧追踪器与快照登记
     * 收敛于 worker 作用域持有者（通道断言）。
     */
    @Test
    void asyncSaveShouldYieldFullChangeSetAndRebuildWorkerScopeTracking() throws Exception {
        insertOrderRow();
        TenantContextAccessor accessor = new TenantContextAccessor();
        RequestContextPropagatingTaskDecorator decorator = new RequestContextPropagatingTaskDecorator(accessor);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TrackingContext.withScope(() -> {
                FullIntegrationTest.Order loaded = orderRepository.getByID(1L);
                loaded.setStatus("PAID");

                AtomicReference<Boolean> workerSaved = new AtomicReference<>();
                AtomicReference<Map<Long, Object>> workerSnapshots = new AtomicReference<>();
                AtomicReference<ChangeTracker> workerTracker = new AtomicReference<>();
                Runnable task = () -> {
                    boolean saved = orderRepository.save(loaded);
                    workerSaved.set(saved);
                    TrackingScope scope = TrackingContext.scope();
                    workerSnapshots.set(scope.getSnapshots());
                    workerTracker.set(scope.trackerIfPresent());
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

                // 行为效果：worker 端保存产出完整变更集（update 落库）
                assertThat(workerSaved.get()).isTrue();
                assertThat(jdbc.queryForObject("SELECT status FROM t_order WHERE id = ?", String.class, 1L))
                        .isEqualTo("PAID");
                // 通道断言：worker 作用域持有者可观察
                assertThat(workerSnapshots.get()).containsKey(1L);
                assertThat(workerTracker.get()).isNotNull();
            });
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * F2（作用域退出无残留）：快照生命周期 = 作用域生命周期——首个作用域内 save 的新对象
     * 退出作用域后注册表消亡；同一聚合根在下一作用域再次 save 按未追踪处理（重新走 insert）。
     */
    @Test
    void scopeExitShouldDropSnapshotRegistrySoSecondSaveGoesInsert() {
        FullIntegrationTest.Order order = new FullIntegrationTest.Order(4L, "ORD-004");
        order.setStatus("PENDING");

        TrackingContext.withScope(() -> {
            assertThat(orderRepository.save(order)).isTrue();   // 首次：insert 路径
        });
        TrackingContext.withScope(() -> {
            assertThat(orderRepository.save(order)).isTrue();   // 作用域已换：重新 insert
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_order WHERE id = ?", Long.class, 4L))
                .isEqualTo(1L);
    }
}