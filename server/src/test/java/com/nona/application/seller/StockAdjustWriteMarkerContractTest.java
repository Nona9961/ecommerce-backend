package com.nona.application.seller;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.factory.InventoryItemFactory;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.InventoryLogJpaRepository;
import com.nona.inf.replica.LastWriteMarker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.verify;

/**
 * 库存调整写后埋点契约测试（调整可售影响搜索「有货」过滤，落库后
 * 标记操作人——TD-08 写用例）。
 * <p>
 * 契约：adjustStock 成功后调用 {@link LastWriteMarker#markWrite(Long)}
 * 标记<b>操作人</b>（adjustStock 操作人参数 = 认证上下文身份，写者
 * 本人即搜索调用者）——商家调库存后 3s 内搜索立即可见有货态变化。
 * <p>
 * fixture：真实仓储链路（H2 + 真实 Repository，同
 * InventoryUseCaseAdjustIntegrationTest）。红阶段：埋点调用待落实
 * （markWrite 未被调用），失败原因 = 实现缺失（埋点缺失）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class StockAdjustWriteMarkerContractTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9601";

    /**
     * 测试店铺 ID（与租户值一致）
     */
    private static final long SHOP_A = 9601L;

    /**
     * 测试 SKU（归属 A 店铺）
     */
    private static final long SKU = 881601L;

    /**
     * 操作人身份（认证上下文，埋点主体）
     */
    private static final long OPERATOR_UID = 96001L;

    /**
     * 被测商家端库存用例
     */
    @Autowired
    private InventoryUseCase inventoryUseCase;

    /**
     * 库存聚合根工厂（初始库存装配）
     */
    @Autowired
    private InventoryItemFactory inventoryItemFactory;

    /**
     * 库存聚合根仓储（初始库存落库）
     */
    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    /**
     * 库存主表 JPA（清理）
     */
    @Autowired
    private InventoryItemJpaRepository inventoryItemJpaRepository;

    /**
     * 流水表 JPA（清理）
     */
    @Autowired
    private InventoryLogJpaRepository inventoryLogJpaRepository;

    /**
     * 编程式事务模板（初始库存装配）
     */
    @Autowired
    private TransactionTemplate tx;

    /**
     * 提权工具（测试数据清理需要越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 写后窗口埋点（mock，断言调整后操作人被标记）
     */
    @MockitoBean
    private LastWriteMarker lastWriteMarker;

    /**
     * 每用例前：提权清空库存/流水两表。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            inventoryLogJpaRepository.deleteAll();
            inventoryItemJpaRepository.deleteAll();
        });
    }

    /**
     * happy：库存调整（增可售）→ 标记操作人账号写后窗口
     * （markWrite(操作人)）。
     */
    @Test
    @DisplayName("库存调整后标记操作人写后窗口")
    void adjustStock_marksOperatorWindow() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT_A);
            seededItem(10);

            inventoryUseCase.adjustStock(SHOP_A, SKU, 5, String.valueOf(OPERATOR_UID), "补货入库");

            verify(lastWriteMarker).markWrite(OPERATOR_UID);
        });
    }

    /**
     * 装配初始库存（可售 = 指定量，不经用例——聚焦调整路径埋点）。
     *
     * @param available 初始可售量
     * @return 装配完成的库存聚合
     */
    private InventoryItem seededItem(int available) {
        return tx.execute(status -> {
            final InventoryItem created = inventoryItemFactory.createInitial(SHOP_A, SKU);
            inventoryItemRepository.save(created);
            final InventoryItem loaded = inventoryItemRepository.getByID(created.getId());
            loaded.adjust("test-initializer", available, "测试初始库存");
            inventoryItemRepository.save(loaded);
            return loaded;
        });
    }
}