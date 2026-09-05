package com.nona.inf.persistence.repository;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.domain.inventory.factory.InventoryItemFactory;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import com.nona.inf.persistence.po.inventory.InventoryLogPO;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.InventoryLogJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存聚合根仓储集成测试：inventory_item 主表存在性、三态/版本落库读回、
 * 变更 + 流水同事务编排（D-1 落库形态：聚合保存与流水追加同事务——
 * 任一失败整体回滚）、SKU 业务键读取与跨店铺租户隔离（fail-closed）。
 * <p>
 * 与 DDD 红线对应：InventoryItem 为独立聚合根，根表 inventory_item
 * （tenant=shopId，sku_id 业务唯一）一行=一聚合实例；仓储继承
 * DifferRepository（读 → track → save → calculateChanges 变更集落库）；
 * 单表聚合无集合子实体（流水独立从表，由 InventoryLogRepository 承载）；
 * 跨店铺加载在主键/SKU 键路径即被租户过滤拦截（fail-closed）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class InventoryItemRepositoryIntegrationTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9101";

    /**
     * 测试店铺 B 的租户（隔离断言用）
     */
    private static final String TENANT_B = "9102";

    /**
     * 测试 SKU（归属 A 店铺）
     */
    private static final long SKU_A = 880101L;

    /**
     * 测试订单（变更流水订单上下文）
     */
    private static final long ORDER_ID = 660101L;

    /**
     * 库存聚合根仓储（被测对象：DifferRepository 子类）
     */
    @Autowired
    private InventoryItemRepositoryImpl inventoryItemRepository;

    /**
     * 库存流水仓储（同事务编排断言用）
     */
    @Autowired
    private InventoryLogRepositoryImpl inventoryLogRepository;

    /**
     * 库存主表 JPA（断言根表行与清理）
     */
    @Autowired
    private InventoryItemJpaRepository inventoryItemJpaRepository;

    /**
     * 流水表 JPA（断言流水行与清理）
     */
    @Autowired
    private InventoryLogJpaRepository inventoryLogJpaRepository;

    /**
     * 库存聚合根工厂
     */
    @Autowired
    private InventoryItemFactory inventoryItemFactory;

    /**
     * 编程式事务模板（模拟用例层事务边界：变更 + 流水同事务编排）
     */
    @Autowired
    private TransactionTemplate tx;


    /**
     * 提权工具（测试数据清理需要越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：提权清空两表 + 建立请求作用域 + 商家 A 租户上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            inventoryLogJpaRepository.deleteAll();
            inventoryItemJpaRepository.deleteAll();
        });
    }

    /**
     * 新增：初始化路径建行——save 后根表存在一行，三态清零/版本 0，
     * 聚合可整体读回（getByID 与 getBySkuId 双路径一致）。
     */
    @Test
    @DisplayName("初始化后根表存在且读回三态清零")
    void save_insertsRootRowReadable() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = tx.execute(status -> {
                final InventoryItem created = inventoryItemFactory.createInitial(9101L, SKU_A);
                inventoryItemRepository.save(created);
                return created;
            });
            assertThat(item).isNotNull();

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getTenantID()).isEqualTo(TENANT_A);
            assertThat(po.getSkuId()).isEqualTo(SKU_A);
            assertThat(po.getAvailable()).isZero();
            assertThat(po.getHeld()).isZero();
            assertThat(po.getSold()).isZero();
            assertThat(po.getVersion()).isZero();

            final InventoryItem byId = inventoryItemRepository.getByID(item.getId());
            assertThat(byId).isNotNull();
            assertThat(byId.getShopId()).isEqualTo(9101L);
            assertThat(byId.getSkuId()).isEqualTo(SKU_A);
            assertThat(byId.getAvailable()).isZero();

            final InventoryItem bySku = inventoryItemRepository.getBySkuId(SKU_A);
            assertThat(bySku).isNotNull();
            assertThat(bySku.getId()).isEqualTo(item.getId());
    
        });
}

    /**
     * 变更：读取（快照基线）→ 变更推进三态/版本 → save 落库，根表行
     * 与聚合一致（乐观锁版本逐笔 +1 后的读回值）。
     */
    @Test
    @DisplayName("变更推进三态与版本并落库")
    void save_updatesThreeStatesAndVersion() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem created = tx.execute(status -> {
                final InventoryItem fresh = inventoryItemFactory.createInitial(9101L, SKU_A);
                inventoryItemRepository.save(fresh);
                return fresh;
            });
            assertThat(created).isNotNull();

            final InventoryItem updated = tx.execute(status -> {
                final InventoryItem loaded = inventoryItemRepository.getByID(created.getId());
                loaded.adjust("72001", 10, "首次补货");
                inventoryItemRepository.save(loaded);
                return loaded;
            });
            assertThat(updated).isNotNull();

            final InventoryItemPO po = inventoryItemJpaRepository.findById(created.getId()).orElseThrow();
            assertThat(po.getAvailable()).isEqualTo(10);
            assertThat(po.getHeld()).isZero();
            assertThat(po.getSold()).isZero();
            assertThat(po.getVersion()).isEqualTo(1);

            final InventoryItem reloaded = inventoryItemRepository.getByID(created.getId());
            assertThat(reloaded.getAvailable()).isEqualTo(10);
            assertThat(reloaded.getVersion()).isEqualTo(1);
    
        });
}

    /**
     * D-1 落库形态：变更 + 流水同事务编排——聚合保存与流水追加在
     * 同一事务内完成，提交后根表行与流水行同时在库（任一失败整体
     * 回滚的编排面由用例层事务承载，本测试以事务模板模拟）。
     */
    @Test
    @DisplayName("变更与流水同事务落库")
    void changeAndLog_sameTransactionPersisted() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem created = tx.execute(status -> {
                final InventoryItem fresh = inventoryItemFactory.createInitial(9101L, SKU_A);
                inventoryItemRepository.save(fresh);
                return fresh;
            });
            assertThat(created).isNotNull();

            final InventoryLog log = tx.execute(status -> {
                final InventoryItem loaded = inventoryItemRepository.getByID(created.getId());
                loaded.adjust("72001", 10, "测试补货");
                inventoryItemRepository.save(loaded);
                final InventoryItem reloaded = inventoryItemRepository.getByID(created.getId());
                final InventoryLog produced = reloaded.preoccupy(ORDER_ID, 3);
                inventoryItemRepository.save(reloaded);
                inventoryLogRepository.append(produced);
                return produced;
            });
            assertThat(log).isNotNull();

            final InventoryItemPO itemPo = inventoryItemJpaRepository.findById(created.getId()).orElseThrow();
            assertThat(itemPo.getAvailable()).isEqualTo(7);
            assertThat(itemPo.getHeld()).isEqualTo(3);
            assertThat(itemPo.getVersion()).isEqualTo(2);

            assertThat(inventoryLogJpaRepository.findById(log.getId())).isPresent();
            final InventoryLogPO logPo = inventoryLogJpaRepository.findById(log.getId()).orElseThrow();
            assertThat(logPo.getType()).isEqualTo(InventoryLogType.PREOCCUPY);
            assertThat(logPo.getOrderId()).isEqualTo(ORDER_ID);
            assertThat(logPo.getSkuId()).isEqualTo(SKU_A);
            assertThat(logPo.getBeforeAvailable()).isEqualTo(10);
            assertThat(logPo.getAfterAvailable()).isEqualTo(7);
            assertThat(logPo.getBeforeHeld()).isZero();
            assertThat(logPo.getAfterHeld()).isEqualTo(3);
            assertThat(logPo.getBeforeSold()).isZero();
            assertThat(logPo.getAfterSold()).isZero();
    
        });
}

    /**
     * 读回：变更一次后再读，聚合状态与根表一致（三态与版本读回语义）。
     */
    @Test
    @DisplayName("变更读回与根表一致")
    void reload_reflectsPersistedState() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem created = tx.execute(status -> {
                final InventoryItem fresh = inventoryItemFactory.createInitial(9101L, SKU_A);
                inventoryItemRepository.save(fresh);
                return fresh;
            });
            assertThat(created).isNotNull();
            tx.executeWithoutResult(status -> {
                final InventoryItem loaded = inventoryItemRepository.getByID(created.getId());
                loaded.adjust("72001", 5, null);
                inventoryItemRepository.save(loaded);
            });

            final InventoryItem reloaded = inventoryItemRepository.getByID(created.getId());
            assertThat(reloaded.getAvailable()).isEqualTo(5);
            assertThat(reloaded.getHeld()).isZero();
            assertThat(reloaded.getSold()).isZero();
            assertThat(reloaded.getVersion()).isEqualTo(1);
    
        });
}

    /**
     * 隔离：B 店铺租户上下文按 SKU 键读取 A 店铺库存 → null（SKU 键
     * 路径 fail-closed，跨店铺归属不泄露）。
     */
    @Test
    @DisplayName("跨店铺按SKU读取不可见（fail-closed）")
    void foreignSku_notVisibleBySkuKey() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem created = tx.execute(status -> {
                final InventoryItem fresh = inventoryItemFactory.createInitial(9101L, SKU_A);
                inventoryItemRepository.save(fresh);
                return fresh;
            });
            assertThat(created).isNotNull();

            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                assertThat(inventoryItemRepository.getByID(created.getId())).isNull();
                assertThat(inventoryItemRepository.getBySkuId(SKU_A)).isNull();
    
            });
        });
}

    /**
     * 归属：B 店铺租户上下文新增库存落库为 B 店归属（tenant 列=B），
     * 切回 A 上下文按 SKU 键读不到 B 店库存。
     */
    @Test
    @DisplayName("库存归属写入当前店铺租户")
    void createUnderTenantB_writesTenantB() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                final InventoryItem itemB = inventoryItemFactory.createInitial(9102L, 880202L);
                inventoryItemRepository.save(itemB);

                final InventoryItemPO saved = inventoryItemJpaRepository.findById(itemB.getId()).orElseThrow();
                assertThat(saved.getTenantID()).isEqualTo(TENANT_B);

                TrackingContext.withScope(() -> {
                    TrackingContext.scope().setTenantID(TENANT_A);
                    assertThat(inventoryItemJpaRepository.findById(itemB.getId())).isEmpty();
                    assertThat(inventoryItemRepository.getBySkuId(880202L)).isNull();
    
                });
            });
        });
}

    /**
     * 删除：deleteByID 返回真实删除条数（1）；不存在返回 0（非契约形）。
     */
    @Test
    @DisplayName("删除返回真实行数")
    void deleteByID_returnsRealRowCount() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem created = tx.execute(status -> {
                final InventoryItem fresh = inventoryItemFactory.createInitial(9101L, SKU_A);
                inventoryItemRepository.save(fresh);
                return fresh;
            });
            assertThat(created).isNotNull();

            final int deleted = tx.execute(status -> inventoryItemRepository.deleteByID(created.getId()));
            assertThat(deleted).isEqualTo(1);
            assertThat(inventoryItemJpaRepository.existsById(created.getId())).isFalse();

            final int deletedAgain = tx.execute(status -> inventoryItemRepository.deleteByID(created.getId()));
            assertThat(deletedAgain).isZero();
    
        });
}

    /**
     * 无变更保存返回 false（快照基线一致时无落库动作——DifferRepository
     * 语义）。
     */
    @Test
    @DisplayName("无变更保存返回false")
    void save_withoutChangesReturnsFalse() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem created = tx.execute(status -> {
                final InventoryItem fresh = inventoryItemFactory.createInitial(9101L, SKU_A);
                inventoryItemRepository.save(fresh);
                return fresh;
            });
            assertThat(created).isNotNull();

            final InventoryItem loaded = inventoryItemRepository.getByID(created.getId());
            assertThat(inventoryItemRepository.save(loaded)).isFalse();
    
        });
}
}