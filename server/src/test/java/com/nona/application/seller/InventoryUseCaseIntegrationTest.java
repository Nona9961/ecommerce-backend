package com.nona.application.seller;

import com.nona.application.seller.InventoryUseCase;
import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.inf.context.ThreadContext;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import com.nona.inf.persistence.po.inventory.InventoryLogPO;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商家端库存用例集成测试：SKU 库存显式初始化入口（D-5）的真实链路——
 * 初始化为三态清零行落库（inventory_item.sku_id 唯一）、用例守卫重复
 * 初始化拒绝（INVENTORY_ALREADY_EXISTS），并发兜底由表级唯一约束承载
 * （见 InventoryUniqueConstraintTest）。初始化无变更语义不产流水。
 * <p>
 * 事务边界在用例方法（@Transactional）；当前店铺由认证上下文定位（本
 * 测试以 ThreadContext.tenantID 模拟商家请求租户=当前店铺）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class InventoryUseCaseIntegrationTest {

    /**
     * 测试店铺的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT = "9401";

    /**
     * 测试 SKU（归属当前店铺）
     */
    private static final long SKU_ID = 880401L;

    /**
     * 被测库存用例
     */
    @Autowired
    private InventoryUseCase inventoryUseCase;

    /**
     * 库存主表 JPA（断言行与清理）
     */
    @Autowired
    private InventoryItemJpaRepository inventoryItemJpaRepository;

    /**
     * 流水表 JPA（断言初始化不产流水与清理）
     */
    @Autowired
    private InventoryLogJpaRepository inventoryLogJpaRepository;

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
     * 每用例前：提权清空两表 + 建立请求作用域 + 商家租户上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            inventoryLogJpaRepository.deleteAll();
            inventoryItemJpaRepository.deleteAll();
        });
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        threadContext.setTenantID(TENANT);
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
     * happy：初始化 SKU 库存——建行三态清零/版本 0、归属当前店铺租户，
     * 不产流水（初始化无变更语义）。
     */
    @Test
    @DisplayName("初始化库存三态清零且不产流水")
    void initialize_createsZeroedRowWithoutLog() {
        final InventoryItem item = inventoryUseCase.initializeStock(9401L, SKU_ID);

        final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
        assertThat(po.getTenantID()).isEqualTo(TENANT);
        assertThat(po.getSkuId()).isEqualTo(SKU_ID);
        assertThat(po.getAvailable()).isZero();
        assertThat(po.getHeld()).isZero();
        assertThat(po.getSold()).isZero();
        assertThat(po.getVersion()).isZero();
        assertThat(inventoryLogJpaRepository.count()).isZero();
    }

    /**
     * error：重复初始化拒绝——用例守卫（按 SKU 查重）抛业务冲突
     * INVENTORY_ALREADY_EXISTS（并发重复由表级唯一约束兜底）。
     */
    @Test
    @DisplayName("重复初始化拒绝")
    void initialize_duplicateRejected() {
        inventoryUseCase.initializeStock(9401L, SKU_ID);

        assertThatThrownBy(() -> inventoryUseCase.initializeStock(9401L, SKU_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_ALREADY_EXISTS.code());

        assertThat(inventoryItemJpaRepository.count()).isEqualTo(1);
    }
}