package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.domain.catalog.entity.ProductImage;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品工厂单元测试：草稿创建与子实体创建的 ID 生成/归属定型/防御校验。
 * <p>
 * 覆盖：happy（创建草稿定型 DRAFT 与归属、子实体绑定商品 ID）、
 * error（店铺缺失拒绝、空名称拒绝、空 URL 拒绝、空属性键拒绝、商品对象缺失拒绝）。
 */
class ProductFactoryTest {

    /**
     * 被测工厂
     */
    private final ProductFactory factory = new ProductFactory();

    /**
     * happy：创建草稿——ID 生成、归属店铺定型、初始状态 DRAFT。
     */
    @Test
    @DisplayName("创建草稿定型 ID/归属/状态")
    void createDraft_assignsIdentityAndDraftStatus() {
        final Product product = factory.createDraft(1L, "测试商品", "描述", 2001L, 3001L);

        assertThat(product.getId()).isNotNull();
        assertThat(product.getShopId()).isEqualTo(1L);
        assertThat(product.getName()).isEqualTo("测试商品");
        assertThat(product.getDescription()).isEqualTo("描述");
        assertThat(product.getCategoryId()).isEqualTo(2001L);
        assertThat(product.getBrandId()).isEqualTo(3001L);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.DRAFT);
        assertThat(product.imagesOrdered()).isEmpty();
        assertThat(product.attributesOrdered()).isEmpty();
    }

    /**
     * error：归属店铺缺失与空名称创建拒绝。
     */
    @Test
    @DisplayName("店铺缺失与空名称创建拒绝")
    void createDraft_defensiveRejections() {
        assertThatThrownBy(() -> factory.createDraft(null, "测试商品", null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("店铺不能为空");
        assertThatThrownBy(() -> factory.createDraft(1L, " ", null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商品名称不能为空");
    }

    /**
     * happy：创建图片引用——独立 ID、绑定归属商品、主图标记透传。
     */
    @Test
    @DisplayName("创建图片引用绑定商品并透传主图标记")
    void createImage_bindsProduct() {
        final Product product = factory.createDraft(1L, "测试商品", null, null, null);

        final ProductImage image = factory.createImage(product, "/files/a.png", true);

        assertThat(image.getId()).isNotNull();
        assertThat(image.getProductId()).isEqualTo(product.getId());
        assertThat(image.getUrl()).isEqualTo("/files/a.png");
        assertThat(image.isPrimary()).isTrue();
    }

    /**
     * error：商品对象缺失与空 URL 的图片创建拒绝。
     */
    @Test
    @DisplayName("商品缺失与空URL的图片创建拒绝")
    void createImage_defensiveRejections() {
        assertThatThrownBy(() -> factory.createImage(null, "/files/a.png", false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商品不能为空");
        final Product product = factory.createDraft(1L, "测试商品", null, null, null);
        assertThatThrownBy(() -> factory.createImage(product, "  ", false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片URL不能为空");
    }

    /**
     * happy：创建属性——独立 ID、绑定归属商品、值可空。
     */
    @Test
    @DisplayName("创建属性绑定商品且值可空")
    void createAttribute_bindsProduct() {
        final Product product = factory.createDraft(1L, "测试商品", null, null, null);

        final ProductAttribute attribute = factory.createAttribute(product, "材质", null);

        assertThat(attribute.getId()).isNotNull();
        assertThat(attribute.getProductId()).isEqualTo(product.getId());
        assertThat(attribute.getKey()).isEqualTo("材质");
        assertThat(attribute.getValue()).isNull();
    }

    /**
     * error：商品对象缺失与空键的属性创建拒绝。
     */
    @Test
    @DisplayName("商品缺失与空键的属性创建拒绝")
    void createAttribute_defensiveRejections() {
        assertThatThrownBy(() -> factory.createAttribute(null, "材质", "纯棉"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商品不能为空");
        final Product product = factory.createDraft(1L, "测试商品", null, null, null);
        assertThatThrownBy(() -> factory.createAttribute(product, " ", "纯棉"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("属性键不能为空");
    }
}