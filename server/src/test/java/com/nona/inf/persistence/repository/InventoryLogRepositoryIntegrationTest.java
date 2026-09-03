package com.nona.inf.persistence.repository;

import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.inf.context.ThreadContext;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import com.nona.inf.persistence.po.inventory.InventoryLogPO;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.InventoryLogJpaRepository;
import com.nona.util.IDUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存流水仓储集成测试：inventory_log 从表 append-only 落库读回
 * （before/after 与聚合一致）、按 SKU 分页（新行在前）/计数、行级删除
 * 拒绝（append-only 语义）与跨店铺租户隔离（fail-closed）。
 * <p>
 * 与 DDD 红线对应：流水行是 InventoryItem 聚合的 append-only 记录
 * （行级删除语义不存在——delete/deleteByID 拒绝）；powered by
 * InventoryLogRepositoryImpl（不继承 DifferRepository——流水行不进聚合
 * 内存，无变更追踪对象）；幂等键 (order_id, sku_id, type) 唯一约束兜底
 * 并发重复追加（DB 级防线）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class InventoryLogRepositoryIntegrationTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9201";

    /**
     * 测试店铺 B 的租户（隔离断言用）
     */
    private static final String TENANT_B = "9202";

    /**
     * 测试 SKU（归属 A 店铺）
     */
    private static final long SKU_A = 880301L;

    /**
     * 测试订单（流水订单上下文）
     */
    private static final long ORDER_ID = 660301L;

    /**
     * 流水仓储（被测对象）
     */
    @Autowired
    private InventoryLogRepositoryImpl inventoryLogRepository;

    /**
     * 流水表 JPA（断言流水行与清理）
     */
    @Autowired
    private InventoryLogJpaRepository inventoryLogJpaRepository;

    /**
     * 库存主表 JPA（清理——流水行租户隔离测试不需库存行，仅清理用）
     */
    @Autowired
    private InventoryItemJpaRepository inventoryItemJpaRepository;

    /**
     * 编程式事务模板（模拟用例层事务边界：append 在事务内）
     */
    @Autowired
    private TransactionTemplate tx;

    /**
     * 请求级上下文（模拟商家请求租户=当前店铺）
     */
    @Autowired
    private ThreadContext threadContext;

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
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        threadContext.setTenantID(TENANT_A);
    }

    /**
     * 每用例后：清理请求作用域与租户上下文，避免跨用例污染。
     */
    @AfterEach
    void tearDown() {
        threadContext.setTenantID(null);
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 追加：append 落库后流水行在库，字段与聚合一致（type/delta/
     * orderId/before/after/operator），读回含审计时间，tenant 归属=A。
     */
    @Test
    @DisplayName("追加落库读回与聚合一致")
    void append_persistsRowReadable() {
        final InventoryLog log = tx.execute(status -> {
            final InventoryLog produced = new InventoryLog(
                    IDUtils.generateID(), 9201L, SKU_A, InventoryLogType.PREOCCUPY, 3, ORDER_ID,
                    10, 0, 0, 7, 3, 0, null, null);
            return inventoryLogRepository.append(produced);
        });
        assertThat(log).isNotNull();

        final InventoryLogPO po = inventoryLogJpaRepository.findById(log.getId()).orElseThrow();
        assertThat(po.getTenantID()).isEqualTo(TENANT_A);
        assertThat(po.getSkuId()).isEqualTo(SKU_A);
        assertThat(po.getType()).isEqualTo(InventoryLogType.PREOCCUPY);
        assertThat(po.getDelta()).isEqualTo(3);
        assertThat(po.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(po.getBeforeAvailable()).isEqualTo(10);
        assertThat(po.getBeforeHeld()).isZero();
        assertThat(po.getBeforeSold()).isZero();
        assertThat(po.getAfterAvailable()).isEqualTo(7);
        assertThat(po.getAfterHeld()).isEqualTo(3);
        assertThat(po.getAfterSold()).isZero();
        assertThat(po.getOperator()).isNull();
        assertThat(po.getCreateTime()).isNotNull();

        final InventoryLog reloaded = inventoryLogRepository.getByID(log.getId());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getShopId()).isEqualTo(9201L);
        assertThat(reloaded.getType()).isEqualTo(InventoryLogType.PREOCCUPY);
        assertThat(reloaded.getBeforeAvailable()).isEqualTo(10);
        assertThat(reloaded.getAfterHeld()).isEqualTo(3);
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    /**
     * 追加：手动调整型流水（orderId 空、operator 必填）读回一致。
     */
    @Test
    @DisplayName("手动调整流水落库读回")
    void append_manualAdjustRowReadable() {
        final InventoryLog log = tx.execute(status -> {
            final InventoryLog produced = new InventoryLog(
                    IDUtils.generateID(), 9201L, SKU_A, InventoryLogType.MANUAL_ADJUST, -4, null,
                    10, 0, 0, 6, 0, 0, "72001", "压货清理");
            return inventoryLogRepository.append(produced);
        });
        assertThat(log).isNotNull();

        final InventoryLogPO po = inventoryLogJpaRepository.findById(log.getId()).orElseThrow();
        assertThat(po.getOrderId()).isNull();
        assertThat(po.getOperator()).isEqualTo("72001");
        assertThat(po.getReason()).isEqualTo("压货清理");
        assertThat(po.getBeforeAvailable()).isEqualTo(10);
        assertThat(po.getAfterAvailable()).isEqualTo(6);
    }

    /**
     * 查询：listBySkuPaged 按 ID 倒序返回本店流水（新行在前），
     * countBySku 计数一致。
     */
    @Test
    @DisplayName("按SKU分页新行在前")
    void listBySkuPaged_newestFirst() {
        final InventoryLog first = appendOccupy("72001", 1, 660301L);
        final InventoryLog second = appendOccupy("72001", 2, 660302L);
        final InventoryLog third = appendOccupy("72001", 3, 660303L);

        final List<InventoryLog> page = inventoryLogRepository.listBySkuPaged(SKU_A, 0, 2);
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getId()).isEqualTo(third.getId());
        assertThat(page.get(1).getId()).isEqualTo(second.getId());

        final List<InventoryLog> secondPage = inventoryLogRepository.listBySkuPaged(SKU_A, 2, 2);
        assertThat(secondPage).hasSize(1);
        assertThat(secondPage.get(0).getId()).isEqualTo(first.getId());

        assertThat(inventoryLogRepository.countBySku(SKU_A)).isEqualTo(3);
    }

    /**
     * 隔离：B 店铺租户上下文按 SKU 查询 A 店铺流水 → 空列表（租户过滤
     * fail-closed）；按行 ID 加载 → null。
     */
    @Test
    @DisplayName("跨店铺流水不可见（fail-closed）")
    void foreignLogs_notVisible() {
        final InventoryLog log = appendOccupy("72001", 1, 660301L);

        threadContext.setTenantID(TENANT_B);
        assertThat(inventoryLogRepository.listBySkuPaged(SKU_A, 0, 10)).isEmpty();
        assertThat(inventoryLogRepository.countBySku(SKU_A)).isZero();
        assertThat(inventoryLogRepository.getByID(log.getId())).isNull();
    }

    /**
     * 行级删除拒绝：delete 与 deleteByID 均抛 UnsupportedOperationException
     * （append-only 流水行无删除语义）。
     */
    @Test
    @DisplayName("行级删除一律拒绝")
    void rowDelete_rejected() {
        final InventoryLog log = appendOccupy("72001", 1, 660301L);

        assertThatThrownBy(() -> inventoryLogRepository.delete(log))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("禁止行级删除");
        assertThatThrownBy(() -> inventoryLogRepository.deleteByID(log.getId()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("禁止行级删除");
        assertThat(inventoryLogJpaRepository.findById(log.getId())).isPresent();
    }

    /**
     * 追加一行预占流水（本店 SKU，事务内）。
     *
     * @param operator 操作人（订单驱动型可空）
     * @param delta    预占数量
     * @param orderId  订单 ID（幂等键按订单区分）
     * @return 追加后的流水行
     */
    private InventoryLog appendOccupy(String operator, int delta, long orderId) {
        return tx.execute(status -> {
            final InventoryLog produced = new InventoryLog(
                    IDUtils.generateID(), 9201L, SKU_A, InventoryLogType.PREOCCUPY, delta, orderId,
                    10, 0, 0, 10 - delta, delta, 0, operator, null);
            return inventoryLogRepository.append(produced);
        });
    }
}