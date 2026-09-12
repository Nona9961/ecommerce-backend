package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.entity.ShopCategory;
import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 店铺聚合根工厂单元测试：店铺与店铺分类的创建入口（ID 生成 + 字段形态校验）。
 * <p>
 * 覆盖：happy（店铺创建带 ID/NORMAL 状态、分类创建带归属店铺）、
 * error（空店铺名拒绝、空分类名拒绝、分类归属店铺缺失拒绝）。
 */
class ShopFactoryUnitTest {

    /**
     * 被测工厂
     */
    private final ShopFactory factory = new ShopFactory();

    /**
     * happy：创建店铺生成 Snowflake ID、初始状态为 NORMAL、字段就位。
     */
    @Test
    @DisplayName("创建店铺生成 ID 且初始状态正常")
    void createShop_generatesIdAndNormalStatus() {
        final Shop shop = factory.createShop("测试店铺", "logo.png", "简介");

        assertThat(shop.getId()).isNotNull();
        assertThat(shop.getName()).isEqualTo("测试店铺");
        assertThat(shop.getLogo()).isEqualTo("logo.png");
        assertThat(shop.getDescription()).isEqualTo("简介");
        assertThat(shop.getStatus()).isEqualTo(ShopStatus.NORMAL);
        assertThat(shop.categoryCount()).isZero();
    }

    /**
     * happy：logo 与简介可空创建。
     */
    @Test
    @DisplayName("logo 与简介可为空创建")
    void createShop_nullableLogoAndDescription() {
        final Shop shop = factory.createShop("测试店铺", null, null);
        assertThat(shop.getLogo()).isNull();
        assertThat(shop.getDescription()).isNull();
    }

    /**
     * error：空店铺名拒绝创建。
     */
    @Test
    @DisplayName("空店铺名拒绝创建")
    void createShop_blankNameRejected() {
        assertThatThrownBy(() -> factory.createShop("  ", "logo.png", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("店铺名称");
    }

    /**
     * error：null 店铺名拒绝创建。
     */
    @Test
    @DisplayName("null 店铺名拒绝创建")
    void createShop_nullNameRejected() {
        assertThatThrownBy(() -> factory.createShop(null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("店铺名称");
    }

    /**
     * happy：创建分类生成独立 ID 并绑定归属店铺。
     */
    @Test
    @DisplayName("创建分类生成 ID 并绑定店铺")
    void createCategory_generatesIdAndBindsShop() {
        final Shop shop = factory.createShop("测试店铺", null, null);
        final ShopCategory category = factory.createCategory(shop, "零食");

        assertThat(category.getId()).isNotNull();
        assertThat(category.getShopId()).isEqualTo(shop.getId());
        assertThat(category.getName()).isEqualTo("零食");
    }

    /**
     * error：空分类名拒绝创建。
     */
    @Test
    @DisplayName("空分类名拒绝创建")
    void createCategory_blankNameRejected() {
        final Shop shop = factory.createShop("测试店铺", null, null);
        assertThatThrownBy(() -> factory.createCategory(shop, " "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分类名称");
    }

    /**
     * error：null 店铺拒绝创建分类（归属不可缺失）。
     */
    @Test
    @DisplayName("null 店铺拒绝创建分类")
    void createCategory_nullShopRejected() {
        assertThatThrownBy(() -> factory.createCategory(null, "零食"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("店铺");
    }
}