package com.nona.application.support;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.domain.inventory.factory.InventoryItemFactory;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import com.nona.inf.persistence.po.inventory.InventoryLogPO;
import com.nona.inf.persistence.repository.InventoryItemRepositoryImpl;
import com.nona.inf.persistence.repository.InventoryLogRepositoryImpl;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.InventoryLogJpaRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存保留用例集成测试：订单驱动三操作（预占/确认扣减/回滚）的全链路
 * 契约——聚合前置守卫 + 仓储条件更新（防超卖并发防线）+ 流水同事务的
 * 三层职责在真实调用链上逐一核验。
 * <ul>
 *     <li>happy：预占成功（三态/version 推进 + PREOCCUPY 流水行）、确认
 *         扣减、回滚恢复；</li>
 *     <li>critical：恰好用尽（库存=需求）、held=0 回滚拒绝、恰好扣尽；</li>
 *     <li>error：超量预占/超 held 扣减拒绝（不动库存不产流水）、幂等键
 *         重复请求拒绝、跨店铺条件更新拒绝（租户条件注入）、唯一约束
 *         兜底路径的异常转换。</li>
 * </ul>
 * 断言一律落库后核验（PO 层直读），流水行按类型过滤（初始化补货产生的
 * MANUAL_ADJUST 行不参与订单流水断言）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class InventoryReservationUseCaseIntegrationAcTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9101";

    /**
     * 测试店铺 B 的租户（跨店铺拒绝断言用）
     */
    private static final String TENANT_B = "9102";

    /**
     * 测试 SKU（归属 A 店铺）
     */
    private static final long SKU = 880102L;

    /**
     * 测试订单（流水订单上下文与幂等键）
     */
    private static final long ORDER = 660102L;

    /**
     * 被测编排用例（事务边界）
     */
    @Autowired
    private InventoryReservationUseCase reservationUseCase;

    /**
     * 库存聚合根仓储（条件更新方法直接断言用）
     */
    @Autowired
    private InventoryItemRepositoryImpl inventoryItemRepository;

    /**
     * 库存流水仓储（幂等兜底断言用）
     */
    @Autowired
    private InventoryLogRepositoryImpl inventoryLogRepository;

    /**
     * 库存主表 JPA（断言行与清理）
     */
    @Autowired
    private InventoryItemJpaRepository inventoryItemJpaRepository;

    /**
     * 流水表 JPA（断言行与清理）
     */
    @Autowired
    private InventoryLogJpaRepository inventoryLogJpaRepository;

    /**
     * 库存聚合根工厂（初始库存装配）
     */
    @Autowired
    private InventoryItemFactory inventoryItemFactory;

    /**
     * 编程式事务模板（初始库存装配：工厂建行 + 手工调整补货）
     */
    @Autowired
    private TransactionTemplate tx;


    /**
     * 提权工具（测试数据清理需要越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：提权清空两表 + 建立请求作用域 + 店铺 A 租户上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            inventoryLogJpaRepository.deleteAll();
            inventoryItemJpaRepository.deleteAll();
        });
    }

    /**
     * happy：预占成功——可售减少、预占增加、version 逐笔 +1，PREOCCUPY
     * 流水一行落库（before/after 三态快照与订单上下文齐备）。
     */
    @Test
    @DisplayName("预占成功：三态推进版本+1且产流水")
    void preoccupy_succeedsWithStateAndLog() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = initializedItem(10);

            final InventoryItem after = reservationUseCase.preoccupy(ORDER, SKU, 4);

            assertThat(after.getAvailable()).isEqualTo(6);
            assertThat(after.getHeld()).isEqualTo(4);
            assertThat(after.getSold()).isZero();
            assertThat(after.getVersion()).isEqualTo(2);

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getAvailable()).isEqualTo(6);
            assertThat(po.getHeld()).isEqualTo(4);
            assertThat(po.getSold()).isZero();
            assertThat(po.getVersion()).isEqualTo(2);

            final List<InventoryLogPO> logs = preoccupyLogs();
            assertThat(logs).hasSize(1);
            final InventoryLogPO log = logs.getFirst();
            assertThat(log.getOrderId()).isEqualTo(ORDER);
            assertThat(log.getSkuId()).isEqualTo(SKU);
            assertThat(log.getType()).isEqualTo(InventoryLogType.PREOCCUPY);
            assertThat(log.getDelta()).isEqualTo(4);
            assertThat(log.getBeforeAvailable()).isEqualTo(10);
            assertThat(log.getBeforeHeld()).isZero();
            assertThat(log.getBeforeSold()).isZero();
            assertThat(log.getAfterAvailable()).isEqualTo(6);
            assertThat(log.getAfterHeld()).isEqualTo(4);
            assertThat(log.getAfterSold()).isZero();
    
        });
}

    /**
     * happy：确认扣减成功——预占减少、已售增加，CONFIRM 流水一行落库
     * （version 随第二笔变更推进到 2）。
     */
    @Test
    @DisplayName("确认扣减成功：预占转已售且version逐笔推进")
    void confirmDeduct_succeeds() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = initializedItem(10);
            reservationUseCase.preoccupy(ORDER, SKU, 4);

            final InventoryItem after = reservationUseCase.confirmDeduct(ORDER, SKU, 3);

            assertThat(after.getAvailable()).isEqualTo(6);
            assertThat(after.getHeld()).isEqualTo(1);
            assertThat(after.getSold()).isEqualTo(3);
            assertThat(after.getVersion()).isEqualTo(3);

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getHeld()).isEqualTo(1);
            assertThat(po.getSold()).isEqualTo(3);
            assertThat(po.getVersion()).isEqualTo(3);

            final List<InventoryLogPO> confirms = logsOf(InventoryLogType.CONFIRM);
            assertThat(confirms).hasSize(1);
            final InventoryLogPO log = confirms.getFirst();
            assertThat(log.getOrderId()).isEqualTo(ORDER);
            assertThat(log.getDelta()).isEqualTo(3);
            assertThat(log.getBeforeHeld()).isEqualTo(4);
            assertThat(log.getAfterHeld()).isEqualTo(1);
            assertThat(log.getAfterSold()).isEqualTo(3);
    
        });
}

    /**
     * happy：回滚恢复——预占全部释放，可售回归初始值、预占清零，
     * ROLLBACK 流水一行落库。
     */
    @Test
    @DisplayName("回滚恢复：预占释放可售回归")
    void rollback_restoresAvailable() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = initializedItem(10);
            reservationUseCase.preoccupy(ORDER, SKU, 4);

            final InventoryItem after = reservationUseCase.rollback(ORDER, SKU, 4);

            assertThat(after.getAvailable()).isEqualTo(10);
            assertThat(after.getHeld()).isZero();
            assertThat(after.getSold()).isZero();
            assertThat(after.getVersion()).isEqualTo(3);

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getAvailable()).isEqualTo(10);
            assertThat(po.getHeld()).isZero();
            assertThat(po.getVersion()).isEqualTo(3);

            final List<InventoryLogPO> rollbacks = logsOf(InventoryLogType.ROLLBACK);
            assertThat(rollbacks).hasSize(1);
            final InventoryLogPO log = rollbacks.getFirst();
            assertThat(log.getOrderId()).isEqualTo(ORDER);
            assertThat(log.getDelta()).isEqualTo(4);
            assertThat(log.getBeforeHeld()).isEqualTo(4);
            assertThat(log.getAfterAvailable()).isEqualTo(10);
            assertThat(log.getAfterHeld()).isZero();
    
        });
}

    /**
     * critical：恰好用尽——库存=需求时预占成功并把可售打到 0（边界值
     * 恰好在放行一侧）；随后的超量预占被拒绝且不再动库存。
     */
    @Test
    @DisplayName("恰好用尽：库存等于需求预占成功可售归零")
    void preoccupy_exactExhaustion() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = initializedItem(2);

            final InventoryItem after = reservationUseCase.preoccupy(ORDER, SKU, 2);

            assertThat(after.getAvailable()).isZero();
            assertThat(after.getHeld()).isEqualTo(2);
            assertThat(after.getVersion()).isEqualTo(2);

            assertThatThrownBy(() -> reservationUseCase.preoccupy(ORDER + 1, SKU, 1))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code());

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getAvailable()).isZero();
            assertThat(po.getHeld()).isEqualTo(2);
            assertThat(preoccupyLogs()).hasSize(1);
    
        });
}

    /**
     * critical：确认扣减恰好扣尽——预占=扣减数量时扣减成功并把预占打
     * 到 0（边界值恰好在放行一侧）。
     */
    @Test
    @DisplayName("恰好扣尽：扣减数量等于预占量预占归零")
    void confirmDeduct_exactHeld() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = initializedItem(10);
            reservationUseCase.preoccupy(ORDER, SKU, 3);

            final InventoryItem after = reservationUseCase.confirmDeduct(ORDER, SKU, 3);

            assertThat(after.getHeld()).isZero();
            assertThat(after.getSold()).isEqualTo(3);

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getHeld()).isZero();
            assertThat(po.getSold()).isEqualTo(3);
    
        });
}

    /**
     * critical：held=0 时回滚拒绝——预占已全部扣减后回滚无预占可退，
     * 业务异常拒绝且三态不变。
     */
    @Test
    @DisplayName("预占归零后回滚被拒绝且三态不变")
    void rollback_withZeroHeld_rejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = initializedItem(10);
            reservationUseCase.preoccupy(ORDER, SKU, 2);
            reservationUseCase.confirmDeduct(ORDER, SKU, 2);

            assertThatThrownBy(() -> reservationUseCase.rollback(ORDER, SKU, 1))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code());

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getAvailable()).isEqualTo(8);
            assertThat(po.getHeld()).isZero();
            assertThat(po.getSold()).isEqualTo(2);
    
        });
}

    /**
     * error：超量预占拒绝——需求大于可售时业务异常拒绝，库存三态不动、
     * 不产流水（无变更不留痕）。
     */
    @Test
    @DisplayName("超量预占拒绝且不动库存不产流水")
    void preoccupy_overDemand_rejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = initializedItem(2);

            assertThatThrownBy(() -> reservationUseCase.preoccupy(ORDER, SKU, 3))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code());

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getAvailable()).isEqualTo(2);
            assertThat(po.getHeld()).isZero();
            assertThat(po.getVersion()).isEqualTo(1);
            assertThat(preoccupyLogs()).isEmpty();
    
        });
}

    /**
     * error：扣减超预占拒绝——扣减数量大于当前预占时业务异常拒绝，
     * 预占/已售不动。
     */
    @Test
    @DisplayName("扣减超预占被拒绝")
    void confirmDeduct_overHeld_rejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = initializedItem(10);
            reservationUseCase.preoccupy(ORDER, SKU, 1);

            assertThatThrownBy(() -> reservationUseCase.confirmDeduct(ORDER, SKU, 2))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code());

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getHeld()).isEqualTo(1);
            assertThat(po.getSold()).isZero();
    
        });
}

    /**
     * error：幂等键重复请求拒绝——同一 (order, sku, type) 的第二次预占
     * 被业务异常拒绝（重复请求不重复扣减、不产第二行流水）。
     */
    @Test
    @DisplayName("同幂等键重复预占被拒绝且库存只扣一次")
    void preoccupy_duplicateRejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = initializedItem(10);
            reservationUseCase.preoccupy(ORDER, SKU, 2);

            assertThatThrownBy(() -> reservationUseCase.preoccupy(ORDER, SKU, 2))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_DUPLICATE.code());

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getAvailable()).isEqualTo(8);
            assertThat(po.getHeld()).isEqualTo(2);
            assertThat(po.getVersion()).isEqualTo(2);
            assertThat(preoccupyLogs()).hasSize(1);
    
        });
}

    /**
     * error：跨店铺条件更新拒绝——店铺 B 的请求上下文对店铺 A 的库存
     * 行执行条件更新，租户条件不命中（受影响行数 0），行内容不变
     * （防跨店 CAS 的租户条件注入形态）。条件更新为修改查询，在事务
     * 环境中执行（与用例层事务边界约定一致，事务内语义与编排路径相同）。
     */
    @Test
    @DisplayName("跨店铺条件更新不命中且行内容不变")
    void casPreoccupy_crossTenant_noMatch() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = initializedItem(10);

            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                final int affected = tx.execute(status -> inventoryItemRepository.casPreoccupy(item.getId(), 1));

                assertThat(affected).isZero();
                TrackingContext.withScope(() -> {
                    TrackingContext.scope().setTenantID(TENANT_A);
                    final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
                    assertThat(po.getAvailable()).isEqualTo(10);
                    assertThat(po.getHeld()).isZero();
                    assertThat(preoccupyLogs()).isEmpty();
    
                });
            });
        });
}

    /**
     * error：唯一约束兜底转换——绕过预查询直接追加同幂等键流水
     * （并发窗口形态），断言兜底路径以业务异常呈现而非裸约束异常。
     */
    @Test
    @DisplayName("兜底路径：同幂等键流水追加转换业务异常")
    void duplicateAppend_convertToBusinessException() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = initializedItem(10);
            reservationUseCase.preoccupy(ORDER, SKU, 2);

            final InventoryItem loaded = inventoryItemRepository.getByID(item.getId());
            final InventoryLog duplicated = loaded.preoccupy(ORDER, 1);

            assertThatThrownBy(() -> inventoryLogRepository.append(duplicated))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_DUPLICATE.code());

            assertThat(preoccupyLogs()).hasSize(1);
    
        });
}

    /**
     * 装配初始库存：工厂建行（三态清零）→ 手工调整补货到目标可售
     * （普通保存路径，与条件更新路径分离）。
     *
     * @param available 目标可售量
     * @return 初始库存聚合
     */
    private InventoryItem initializedItem(int available) {
        return tx.execute(status -> {
            final InventoryItem created = inventoryItemFactory.createInitial(9101L, SKU);
            inventoryItemRepository.save(created);
            final InventoryItem loaded = inventoryItemRepository.getByID(created.getId());
            loaded.adjust("test-initializer", available, "测试初始库存");
            inventoryItemRepository.save(loaded);
            return loaded;
        });
    }

    /**
     * 取该 SKU 的 PREOCCUPY 流水行（新行在前）。
     *
     * @return PREOCCUPY 流水行列表
     */
    private List<InventoryLogPO> preoccupyLogs() {
        return logsOf(InventoryLogType.PREOCCUPY);
    }

    /**
     * 取该 SKU 指定类型的流水行（新行在前）。
     *
     * @param type 流水类型
     * @return 指定类型流水行列表
     */
    private List<InventoryLogPO> logsOf(InventoryLogType type) {
        return inventoryLogJpaRepository
                .findBySkuIdOrderByIdDesc(SKU, PageRequest.of(0, 100))
                .stream()
                .filter(log -> log.getType() == type)
                .toList();
    }
}