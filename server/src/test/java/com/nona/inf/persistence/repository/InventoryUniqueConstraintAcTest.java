package com.nona.inf.persistence.repository;

import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
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
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存唯一约束兜底契约测试（DB 级并发防线，聚合守卫/用例守卫外的第二
 * 道保险）：
 * <ul>
 *     <li>inventory_item 表 (sku_id) 唯一（uk_inventory_item_sku）——
 *         并发同 SKU 初始化仅一行成功，绕过查重路径直插第二行被拒绝；
 *         初始化入口用例守卫负责正常路径查重，唯一约束兜底并发/直插路径。</li>
 *     <li>inventory_log 表 (order_id, sku_id, type) 幂等键唯一
 *         （uk_inventory_log_order_sku_type）——同一订单同一 SKU 的同一
 *         类型变动只允许一次，重复追加由 DB 拒绝；不同订单/类型互不冲突。</li>
 * </ul>
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class InventoryUniqueConstraintAcTest {

    /**
     * 测试店铺的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT = "9301";

    /**
     * 库存主表 JPA（直插验证 sku 唯一约束与清理）
     */
    @Autowired
    private InventoryItemJpaRepository inventoryItemJpaRepository;

    /**
     * 流水表 JPA（直插验证幂等键唯一约束与清理）
     */
    @Autowired
    private InventoryLogJpaRepository inventoryLogJpaRepository;


    /**
     * 提权工具（测试数据清理需要越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：提权清空两表 + 建立请求作用域 + 商家租户上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            inventoryLogJpaRepository.deleteAll();
            inventoryItemJpaRepository.deleteAll();
        });
    }

    /**
     * critical：幂等键重复直插拒绝——同一 (order_id, sku_id, type) 的
     * 第二行直插被唯一约束拒绝（防重复预占/扣减/回滚的 DB 级防线）；
     * 首行保持、冲突行不落库。
     */
    @Test
    @DisplayName("同幂等键第二行直插被唯一约束拒绝")
    void duplicateIdempotencyKey_rejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT);
            final long orderId = 660901L;
            final long skuId = 880901L;
            inventoryLogJpaRepository.save(newLogPo(orderId, skuId, 1L, InventoryLogType.PREOCCUPY, 10, 7, 3));

            assertThatThrownBy(() ->
                    inventoryLogJpaRepository.save(newLogPo(orderId, skuId, 2L, InventoryLogType.PREOCCUPY, 7, 4, 2)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(inventoryLogJpaRepository.existsById(1L)).isTrue();
            assertThat(inventoryLogJpaRepository.existsById(2L)).isFalse();
    
        });
}

    /**
     * critical：不同订单的相同 (sku, type) 互不冲突（幂等键按订单维度，
     * 各订单独立幂等）。
     */
    @Test
    @DisplayName("不同订单同SKU同类型合法共存")
    void sameSkuDifferentOrders_allowed() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT);
            inventoryLogJpaRepository.save(newLogPo(660901L, 880901L, 1L, InventoryLogType.PREOCCUPY, 10, 7, 3));
            inventoryLogJpaRepository.save(newLogPo(660902L, 880901L, 2L, InventoryLogType.PREOCCUPY, 10, 7, 3));

            assertThat(inventoryLogJpaRepository.existsById(1L)).isTrue();
            assertThat(inventoryLogJpaRepository.existsById(2L)).isTrue();
    
        });
}

    /**
     * critical：不同流类型的相同 (order_id, sku_id) 互不冲突（同一订单
     * 同一 SKU 的预占/确认/回滚各一行，幂等键含 type 维度）。
     */
    @Test
    @DisplayName("同订单同SKU不同类型合法共存")
    void sameOrderDifferentTypes_allowed() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT);
            inventoryLogJpaRepository.save(newLogPo(660901L, 880901L, 1L, InventoryLogType.PREOCCUPY, 10, 7, 3));
            inventoryLogJpaRepository.save(newLogPo(660901L, 880901L, 2L, InventoryLogType.CONFIRM, 7, 7, 3));

            assertThat(inventoryLogJpaRepository.existsById(1L)).isTrue();
            assertThat(inventoryLogJpaRepository.existsById(2L)).isTrue();
    
        });
}

    /**
     * critical：重复初始化拒绝——同 sku_id 的库存第二行直插被唯一约束
     * 拒绝（uk_inventory_item_sku，模拟并发同 SKU 初始化同时落库）；
     * 首行保持。
     */
    @Test
    @DisplayName("同SKU库存第二行直插被唯一约束拒绝")
    void duplicateSku_rejected() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT);
            final long skuId = 880901L;
            inventoryItemJpaRepository.save(newItemPo(skuId, 1L, 0, 0, 0, 0));

            assertThatThrownBy(() ->
                    inventoryItemJpaRepository.save(newItemPo(skuId, 2L, 0, 0, 0, 0)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(inventoryItemJpaRepository.existsById(1L)).isTrue();
            assertThat(inventoryItemJpaRepository.existsById(2L)).isFalse();
    
        });
}

    /**
     * critical：不同 SKU 的库存行合法共存（唯一约束按 SKU 维度，各 SKU
     * 独立一根行）。
     */
    @Test
    @DisplayName("不同SKU库存行合法共存")
    void differentSkus_allowed() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT);
            inventoryItemJpaRepository.save(newItemPo(880901L, 1L, 0, 0, 0, 0));
            inventoryItemJpaRepository.save(newItemPo(880902L, 2L, 0, 0, 0, 0));

            assertThat(inventoryItemJpaRepository.existsById(1L)).isTrue();
            assertThat(inventoryItemJpaRepository.existsById(2L)).isTrue();
    
        });
}

    /**
     * 构造流水行（直插绕过聚合构造/仓储 append 路径，验证 DB 兜底）。
     *
     * @param orderId 订单 ID
     * @param skuId   SKU ID
     * @param id      行 ID
     * @param type    流水类型
     * @param before  变动前可售
     * @param after   变动后可售
     * @param delta   变动数量
     * @return 流水行
     */
    private static InventoryLogPO newLogPo(long orderId, long skuId, long id,
                                           InventoryLogType type,
                                           int before, int after, int delta) {
        final InventoryLogPO po = new InventoryLogPO();
        po.setId(id);
        po.setSkuId(skuId);
        po.setType(type);
        po.setDelta(delta);
        po.setOrderId(orderId);
        po.setBeforeAvailable(before);
        po.setBeforeHeld(0);
        po.setBeforeSold(0);
        po.setAfterAvailable(after);
        po.setAfterHeld(delta);
        po.setAfterSold(0);
        po.setOperator(null);
        return po;
    }

    /**
     * 构造库存行（直插绕过聚合工厂/仓储路径，验证 DB 兜底）。
     *
     * @param skuId SKU ID
     * @param id    行 ID
     * @return 库存行
     */
    private static InventoryItemPO newItemPo(long skuId, long id,
                                             int available, int held, int sold, int version) {
        final InventoryItemPO po = new InventoryItemPO();
        po.setId(id);
        po.setSkuId(skuId);
        po.setAvailable(available);
        po.setHeld(held);
        po.setSold(sold);
        po.setVersion(version);
        return po;
    }
}