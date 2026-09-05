package com.nona.application.support;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.domain.inventory.factory.InventoryItemFactory;
import com.nona.domain.inventory.ports.InventoryEventPublisher;
import com.nona.domain.inventory.ports.RestockEvent;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 退款回补用例集成测试：restore 全链路契约（第四订单驱动操作，I7——未
 * 发货退款/发货超时关单把已售回补可售）——聚合前置守卫 + 仓储条件更新
 * （防回补超已售并发防线）+ REFUND_RESTORE 流水同事务 + 恢复事件统一
 * 触发点接入。
 * <ul>
 *     <li>happy：回补成功（三态推进/version +1/REFUND_RESTORE 流水
 *         before/after 快照落库）；售罄态 SKU 回补恢复可售发布恢复事件；</li>
 *     <li>critical：恰好回补完（sold 归零边界放行侧）、回补不超已售
 *         （超量拒绝不动库存）、幂等键重复拒绝（sold 只减一次）、同订单
 *         多 SKU 部分回补各自独立判定（售罄 SKU 发恢复事件、非售罄零
 *         事件）、唯一约束兜底路径转换；</li>
 *     <li>error：数量非正/订单缺失/库存不存在/跨店铺按不存在拒绝、
 *         跨店铺条件更新不命中。</li>
 * </ul>
 * 事件断言经 mock 发布端口（统一触发点判定后的发布动作）验证；断言
 * 一律落库后核验（PO 层直读）。「已发货/已完成不回补」语义由退款编排
 * 层按子单状态判定保障（C9），域能力不感知发货状态——本测试不覆盖。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class InventoryRestoreUseCaseIntegrationTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9701";

    /**
     * 测试店铺 B 的租户（跨店铺拒绝断言用）
     */
    private static final String TENANT_B = "9702";

    /**
     * 测试店铺 ID（与租户值一致）
     */
    private static final long SHOP_A = 9701L;

    /**
     * 测试 SKU-A（归属 A 店铺）
     */
    private static final long SKU_A = 882401L;

    /**
     * 测试 SKU-B（归属 A 店铺，部分回补断言用）
     */
    private static final long SKU_B = 882402L;

    /**
     * 测试订单（流水订单上下文与幂等键）
     */
    private static final long ORDER = 662401L;

    /**
     * 被测库存保留用例（第四订单驱动操作 restore）
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
     * 库存聚合根工厂（初始库存装配）
     */
    @Autowired
    private InventoryItemFactory inventoryItemFactory;

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
     * 事件发布端口（mock——统一触发点判定后的发布动作断言点）
     */
    @MockitoBean
    private InventoryEventPublisher inventoryEventPublisher;

    /**
     * 编程式事务模板（初始库存装配：工厂建行 + 聚合调整补货 +
     * CAS 预占 + CAS 确认扣减——已售就位）
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
     * happy：回补成功——已售减少、可售增加、version 逐笔 +1，
     * REFUND_RESTORE 流水一行落库（before/after 三态快照与订单上下文
     * 齐备）；装配形态为售罄态（可售 0/预占 0/已售 5），回补 3 后恢复
     * 可售，恢复事件发布恰好一次（payload/TYPE 断言）。
     */
    @Test
    @DisplayName("回补成功：已售转可售且REFUND_RESTORE流水落库")
    void restore_movesSoldToAvailable_withRefundRestoreLog() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = seededSoldOut(SKU_A, 5);

            final InventoryItem after = reservationUseCase.restore(ORDER, SKU_A, 3);

            assertThat(after.getAvailable()).isEqualTo(3);
            assertThat(after.getHeld()).isZero();
            assertThat(after.getSold()).isEqualTo(2);
            assertThat(after.getVersion()).isEqualTo(4);

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getAvailable()).isEqualTo(3);
            assertThat(po.getHeld()).isZero();
            assertThat(po.getSold()).isEqualTo(2);
            assertThat(po.getVersion()).isEqualTo(4);

            final List<InventoryLogPO> restores = restoreLogs(SKU_A);
            assertThat(restores).hasSize(1);
            final InventoryLogPO log = restores.getFirst();
            assertThat(log.getOrderId()).isEqualTo(ORDER);
            assertThat(log.getSkuId()).isEqualTo(SKU_A);
            assertThat(log.getType()).isEqualTo(InventoryLogType.REFUND_RESTORE);
            assertThat(log.getDelta()).isEqualTo(3);
            assertThat(log.getBeforeAvailable()).isZero();
            assertThat(log.getBeforeHeld()).isZero();
            assertThat(log.getBeforeSold()).isEqualTo(5);
            assertThat(log.getAfterAvailable()).isEqualTo(3);
            assertThat(log.getAfterHeld()).isZero();
            assertThat(log.getAfterSold()).isEqualTo(2);

            final ArgumentCaptor<RestockEvent> restock = ArgumentCaptor.forClass(RestockEvent.class);
            verify(inventoryEventPublisher, times(1)).publishRestock(restock.capture());
            assertThat(restock.getValue().getPayload().skuId()).isEqualTo(SKU_A);
            assertThat(restock.getValue().getType()).isEqualTo(RestockEvent.TYPE);
            verify(inventoryEventPublisher, never()).publishSellout(org.mockito.ArgumentMatchers.any());
        });
    }

    /**
     * happy：非售罄态回补——装配保留可售（未售罄），回补后不发布任何
     * 事件（恢复事件判定逐 SKU 独立：before (0,0) 且 after 非 (0,0) 才
     * 触发，本路径 before 非售罄）。
     */
    @Test
    @DisplayName("非售罄态回补成功且零事件发布")
    void restore_withAvailableLeft_noEvent() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = seededSoldWithAvailable(SKU_A, 2, 3);

            final InventoryItem after = reservationUseCase.restore(ORDER, SKU_A, 2);

            assertThat(after.getAvailable()).isEqualTo(5);
            assertThat(after.getSold()).isZero();
            assertThat(after.getVersion()).isEqualTo(4);

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getAvailable()).isEqualTo(5);
            assertThat(po.getSold()).isZero();

            assertThat(restoreLogs(SKU_A)).hasSize(1);
            verifyNoInteractions(inventoryEventPublisher);
        });
    }

    /**
     * critical：恰好回补完——已售=回补数量时回补成功并把已售打到 0
     * （边界值恰好在放行一侧），恢复事件发布一次。
     */
    @Test
    @DisplayName("恰好回补完：已售归零可售恢复")
    void restore_exactSold_soldZero() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = seededSoldOut(SKU_A, 2);

            final InventoryItem after = reservationUseCase.restore(ORDER, SKU_A, 2);

            assertThat(after.getAvailable()).isEqualTo(2);
            assertThat(after.getSold()).isZero();
            assertThat(after.getVersion()).isEqualTo(4);

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getAvailable()).isEqualTo(2);
            assertThat(po.getSold()).isZero();

            assertThat(restoreLogs(SKU_A)).hasSize(1);
            verify(inventoryEventPublisher, times(1)).publishRestock(org.mockito.ArgumentMatchers.any());
        });
    }

    /**
     * critical：回补不超已售——回补数量大于已售时业务异常拒绝（聚合
     * 前置守卫与仓储条件更新双守卫），已售/可售不动、不产流水、不发
     * 布任何事件（安全保证不回补多于已售）。
     */
    @Test
    @DisplayName("回补超已售被拒绝且三态不动不产流水")
    void restore_overSold_rejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = seededSoldOut(SKU_A, 2);

            assertThatThrownBy(() -> reservationUseCase.restore(ORDER, SKU_A, 3))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code());

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getAvailable()).isZero();
            assertThat(po.getHeld()).isZero();
            assertThat(po.getSold()).isEqualTo(2);
            assertThat(po.getVersion()).isEqualTo(3);
            assertThat(restoreLogs(SKU_A)).isEmpty();
            verifyNoInteractions(inventoryEventPublisher);
        });
    }

    /**
     * critical：幂等键重复拒绝——同一 (order, sku, REFUND_RESTORE) 的
     * 第二次回补被业务异常拒绝（预查询快速拒绝），已售只回补一次、流
     * 水仍一行、恢复事件不重复发布。
     */
    @Test
    @DisplayName("同幂等键重复回补被拒绝且只回补一次")
    void restore_duplicateRejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = seededSoldOut(SKU_A, 3);
            reservationUseCase.restore(ORDER, SKU_A, 2);

            assertThatThrownBy(() -> reservationUseCase.restore(ORDER, SKU_A, 1))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_DUPLICATE.code());

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getAvailable()).isEqualTo(2);
            assertThat(po.getSold()).isEqualTo(1);
            assertThat(po.getVersion()).isEqualTo(4);
            assertThat(restoreLogs(SKU_A)).hasSize(1);
            verify(inventoryEventPublisher, times(1)).publishRestock(org.mockito.ArgumentMatchers.any());
        });
    }

    /**
     * critical：部分 SKU 回补——同一订单两个 SKU 各自独立回补（独立
     * 幂等、独立流水、独立事件判定）：SKU-A 售罄态回补发布恢复事件恰
     * 好一次（B 非售罄零事件——总次数即 A 的一次）；两行 REFUND_RESTORE
     * 流水各就各位（订单上下文一致）。
     */
    @Test
    @DisplayName("同订单多SKU部分回补各自独立判定")
    void restore_partialSkus_independent() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem itemA = seededSoldOut(SKU_A, 2);
            final InventoryItem itemB = seededSoldWithAvailable(SKU_B, 2, 3);

            final InventoryItem afterA = reservationUseCase.restore(ORDER, SKU_A, 2);
            final InventoryItem afterB = reservationUseCase.restore(ORDER, SKU_B, 2);

            assertThat(afterA.getAvailable()).isEqualTo(2);
            assertThat(afterA.getSold()).isZero();
            assertThat(afterB.getAvailable()).isEqualTo(5);
            assertThat(afterB.getSold()).isZero();

            final InventoryItemPO poA = inventoryItemJpaRepository.findById(itemA.getId()).orElseThrow();
            assertThat(poA.getAvailable()).isEqualTo(2);
            assertThat(poA.getSold()).isZero();
            final InventoryItemPO poB = inventoryItemJpaRepository.findById(itemB.getId()).orElseThrow();
            assertThat(poB.getAvailable()).isEqualTo(5);
            assertThat(poB.getSold()).isZero();

            final List<InventoryLogPO> restoresA = restoreLogs(SKU_A);
            final List<InventoryLogPO> restoresB = restoreLogs(SKU_B);
            assertThat(restoresA).hasSize(1);
            assertThat(restoresB).hasSize(1);
            assertThat(restoresA.getFirst().getOrderId()).isEqualTo(ORDER);
            assertThat(restoresB.getFirst().getOrderId()).isEqualTo(ORDER);

            final ArgumentCaptor<RestockEvent> restock = ArgumentCaptor.forClass(RestockEvent.class);
            verify(inventoryEventPublisher, times(1)).publishRestock(restock.capture());
            assertThat(restock.getValue().getPayload().skuId()).isEqualTo(SKU_A);
            verify(inventoryEventPublisher, never()).publishSellout(org.mockito.ArgumentMatchers.any());
        });
    }

    /**
     * critical：兜底路径——绕过幂等预查直接追加同键 REFUND_RESTORE 流
     * 水（并发窗口形态），断言兜底路径以业务异常呈现而非裸约束异常
     * （REFUND_RESTORE 参与幂等键唯一约束，无需 DDL 变更）。
     */
    @Test
    @DisplayName("兜底路径：同键回补流水追加转换业务异常")
    void duplicateAppendRefundRestore_convertToBusinessException() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = seededSoldOut(SKU_A, 5);
            reservationUseCase.restore(ORDER, SKU_A, 3);

            final InventoryItem loaded = inventoryItemRepository.getByID(item.getId());
            final InventoryLog duplicated = loaded.restoreSold(ORDER, 1);

            assertThatThrownBy(() -> inventoryLogRepository.append(duplicated))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_DUPLICATE.code());

            assertThat(restoreLogs(SKU_A)).hasSize(1);
        });
    }

    /**
     * error：回补数量非正拒绝（零/负均无业务意义）——不进入库存变更
     * 路径。
     */
    @Test
    @DisplayName("回补数量非正被拒绝")
    void restore_nonPositiveQuantity_rejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = seededSoldOut(SKU_A, 5);

            assertThatThrownBy(() -> reservationUseCase.restore(ORDER, SKU_A, 0))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_QUANTITY_INVALID.code());
            assertThatThrownBy(() -> reservationUseCase.restore(ORDER, SKU_A, -1))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_QUANTITY_INVALID.code());

            final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
            assertThat(po.getSold()).isEqualTo(5);
            assertThat(restoreLogs(SKU_A)).isEmpty();
        });
    }

    /**
     * error：订单缺失拒绝——幂等键要求订单 ID 必填，缺失直接拒绝且
     * 不产流水。
     */
    @Test
    @DisplayName("回补订单缺失被拒绝")
    void restore_missingOrderId_rejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            seededSoldOut(SKU_A, 5);

            assertThatThrownBy(() -> reservationUseCase.restore(null, SKU_A, 1))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());

            assertThat(restoreLogs(SKU_A)).isEmpty();
        });
    }

    /**
     * error：库存不存在拒绝——未初始化 SKU 的回补按不存在拒绝（缺行
     * 无已售可回补），归属不泄露。
     */
    @Test
    @DisplayName("未初始化SKU回补按不存在拒绝")
    void restore_uninitializedSku_notFound() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);

            assertThatThrownBy(() -> reservationUseCase.restore(ORDER, SKU_A, 1))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.INVENTORY_NOT_FOUND.code());
        });
    }

    /**
     * error：跨店铺回补不可见——店铺 B 的请求上下文对店铺 A 的 SKU
     * 库存执行回补，按不存在拒绝（归属不泄露，fail-closed），行内容
     * 不变、不产流水。
     */
    @Test
    @DisplayName("跨店铺回补按不存在拒绝且行内容不变")
    void restore_crossTenant_notFound() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = seededSoldOut(SKU_A, 5);

            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                assertThatThrownBy(() -> reservationUseCase.restore(ORDER, SKU_A, 1))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.INVENTORY_NOT_FOUND.code());

                TrackingContext.withScope(() -> {
                    TrackingContext.scope().setTenantID(TENANT_A);
                    final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
                    assertThat(po.getAvailable()).isZero();
                    assertThat(po.getSold()).isEqualTo(5);
                    assertThat(po.getVersion()).isEqualTo(3);
                    assertThat(restoreLogs(SKU_A)).isEmpty();
                });
            });
        });
    }

    /**
     * error：跨店铺条件更新不命中——店铺 B 的请求上下文对店铺 A 的
     * 库存行执行回补条件更新，租户条件不命中（受影响行数 0——防跨店
     * CAS 的租户条件注入形态），行内容不变。条件更新为修改查询，在
     * 事务环境中执行（与用例层事务边界约定一致）。
     */
    @Test
    @DisplayName("跨店铺回补条件更新不命中且行内容不变")
    void casRestore_crossTenant_noMatch() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            final InventoryItem item = seededSoldOut(SKU_A, 5);

            TrackingContext.withScope(() -> {
                TrackingContext.scope().setTenantID(TENANT_B);
                final int affected = tx.execute(status -> inventoryItemRepository.casRestore(item.getId(), 1));

                assertThat(affected).isZero();
                TrackingContext.withScope(() -> {
                    TrackingContext.scope().setTenantID(TENANT_A);
                    final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
                    assertThat(po.getAvailable()).isZero();
                    assertThat(po.getSold()).isEqualTo(5);
                    assertThat(restoreLogs(SKU_A)).isEmpty();
                });
            });
        });
    }

    /**
     * 装配售罄态已售库存（可售 0、预占 0、已售=sold）：工厂建行（三态
     * 清零）→ 聚合调整补货到 sold → 条件更新预占全额 → 条件更新确认
     * 扣减全额（已实现路径——已售就位且可售/预占归零）。
     *
     * @param skuId 归属 SKU ID
     * @param sold  目标已售量
     * @return 初始库存聚合（装配路径不含事件发布）
     */
    private InventoryItem seededSoldOut(long skuId, int sold) {
        return tx.execute(status -> {
            final InventoryItem created = inventoryItemFactory.createInitial(SHOP_A, skuId);
            inventoryItemRepository.save(created);
            final InventoryItem loaded = inventoryItemRepository.getByID(created.getId());
            loaded.adjust("test-initializer", sold, "测试初始库存");
            inventoryItemRepository.save(loaded);
            if (inventoryItemRepository.casPreoccupy(loaded.getId(), sold) != 1) {
                throw new IllegalStateException("预占装配失败");
            }
            if (inventoryItemRepository.casConfirmDeduct(loaded.getId(), sold) != 1) {
                throw new IllegalStateException("扣减装配失败");
            }
            return loaded;
        });
    }

    /**
     * 装配非售罄态已售库存（可售=availableLeft 留存、预占 0、已售
     * =sold）：工厂建行 → 聚合调整补货到 (sold + availableLeft) →
     * 条件更新预占 sold → 条件更新确认扣减 sold。
     *
     * @param skuId         归属 SKU ID
     * @param sold          目标已售量
     * @param availableLeft 目标留存可售量
     * @return 初始库存聚合（装配路径不含事件发布）
     */
    private InventoryItem seededSoldWithAvailable(long skuId, int sold, int availableLeft) {
        return tx.execute(status -> {
            final InventoryItem created = inventoryItemFactory.createInitial(SHOP_A, skuId);
            inventoryItemRepository.save(created);
            final InventoryItem loaded = inventoryItemRepository.getByID(created.getId());
            loaded.adjust("test-initializer", sold + availableLeft, "测试初始库存");
            inventoryItemRepository.save(loaded);
            if (inventoryItemRepository.casPreoccupy(loaded.getId(), sold) != 1) {
                throw new IllegalStateException("预占装配失败");
            }
            if (inventoryItemRepository.casConfirmDeduct(loaded.getId(), sold) != 1) {
                throw new IllegalStateException("扣减装配失败");
            }
            return loaded;
        });
    }

    /**
     * 取该 SKU 的 REFUND_RESTORE 流水行（新行在前）。
     *
     * @param skuId 归属 SKU ID
     * @return 回补流水行列表
     */
    private List<InventoryLogPO> restoreLogs(long skuId) {
        return inventoryLogJpaRepository
                .findBySkuIdOrderByIdDesc(skuId, PageRequest.of(0, 100))
                .stream()
                .filter(log -> log.getType() == InventoryLogType.REFUND_RESTORE)
                .toList();
    }
}