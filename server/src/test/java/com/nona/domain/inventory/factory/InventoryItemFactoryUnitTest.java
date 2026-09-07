package com.nona.domain.inventory.factory;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存聚合工厂单元测试：SKU 库存的创建入口（ID 生成 + 三态清零定型）。
 * <p>
 * 覆盖：happy（创建生成 Snowflake ID、归属店铺/SKU 绑定、三态清零、
 * 乐观锁版本 0、创建即可售查询为 0）、error（空归属店铺/空归属 SKU 拒绝
 * ——非法归属的库存行无业务意义）。
 *
 * @author nona9961
 */
class InventoryItemFactoryUnitTest {

    /**
     * 被测工厂
     */
    private final InventoryItemFactory factory = new InventoryItemFactory();

    /**
     * happy：创建库存生成 ID、绑定归属、三态清零、乐观锁版本 0。
     */
    @Test
    @DisplayName("创建库存生成 ID 且三态清零")
    void createInitial_generatesIdAndZeroStates() {
        final InventoryItem item = factory.createInitial(1001L, 88001L);

        assertThat(item.getId()).isNotNull();
        assertThat(item.getShopId()).isEqualTo(1001L);
        assertThat(item.getSkuId()).isEqualTo(88001L);
        assertThat(item.getAvailable()).isZero();
        assertThat(item.getHeld()).isZero();
        assertThat(item.getSold()).isZero();
        assertThat(item.getVersion()).isZero();
    }

    /**
     * happy：创建后即可售查询为 0（创建 = 三态清零初始化，无变更语义，
     * 不产流水；可售面立即可读）。
     */
    @Test
    @DisplayName("创建后查询可售为 0")
    void createInitial_queryAvailableReturnsZero() {
        final InventoryItem item = factory.createInitial(1001L, 88001L);

        assertThat(item.getAvailable()).isZero();
    }

    /**
     * error：空归属店铺拒绝创建。
     */
    @Test
    @DisplayName("空归属店铺拒绝创建")
    void createInitial_nullShopIdRejected() {
        assertThatThrownBy(() -> factory.createInitial(null, 88001L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code());
    }

    /**
     * error：空归属 SKU 拒绝创建。
     */
    @Test
    @DisplayName("空归属 SKU 拒绝创建")
    void createInitial_nullSkuIdRejected() {
        assertThatThrownBy(() -> factory.createInitial(1001L, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code());
    }
}