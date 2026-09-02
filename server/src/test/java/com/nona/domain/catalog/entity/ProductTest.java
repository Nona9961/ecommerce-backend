package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品聚合根单元测试：草稿主体编辑、图片引用管理（增删/设主图）、
 * 自定义属性键值管理（增删改）的领域行为。
 * <p>
 * 覆盖：happy（主体编辑生效、图片增/删/设主图、属性增/删/改）、
 * critical（无主图状态、删除主图后主图位清空、设主图幂等、改回自身键合法、
 * 集合保序不可变）、error（空名称拒绝、空 URL 拒绝、空属性键拒绝、
 * 属性键重复拒绝、目标图片/属性不存在拒绝、重复添加同一 ID 拒绝）。
 */
class ProductTest {

    /**
     * happy：创建草稿（名称必填通过）→ 主体字段与状态 DRAFT 定型。
     */
    @Test
    @DisplayName("创建草稿定型主体字段与 DRAFT 状态")
    void construct_productFieldsAndDraftStatus() {
        final Product product = product("测试商品", null, 2001L, null);

        assertThat(product.getId()).isEqualTo(1001L);
        assertThat(product.getShopId()).isEqualTo(1L);
        assertThat(product.getName()).isEqualTo("测试商品");
        assertThat(product.getDescription()).isNull();
        assertThat(product.getCategoryId()).isEqualTo(2001L);
        assertThat(product.getBrandId()).isNull();
        assertThat(product.getStatus()).isEqualTo(ProductStatus.DRAFT);
        assertThat(product.imagesOrdered()).isEmpty();
        assertThat(product.attributesOrdered()).isEmpty();
    }

    /**
     * error：空名称构造拒绝（含全空白）。
     */
    @Test
    @DisplayName("空名称创建草稿拒绝")
    void construct_blankNameRejected() {
        assertThatThrownBy(() -> new Product(1001L, 1L, "  ", null, null, null, ProductStatus.DRAFT))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商品名称不能为空");
        assertThatThrownBy(() -> new Product(1001L, 1L, null, null, null, null, ProductStatus.DRAFT))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * happy：更新主体（改名/改描述/挂类目与品牌/清空引用）全部生效。
     */
    @Test
    @DisplayName("更新主体生效：名称/描述/引用整体替换")
    void updateInfo_changesBodyAndReferences() {
        final Product product = product("原商品名", "旧描述", 2001L, 3001L);

        product.updateInfo("新商品名", "新描述", 2002L, null);

        assertThat(product.getName()).isEqualTo("新商品名");
        assertThat(product.getDescription()).isEqualTo("新描述");
        assertThat(product.getCategoryId()).isEqualTo(2002L);
        assertThat(product.getBrandId()).isNull();
    }

    /**
     * error：更新主体空名称拒绝（引用与描述提前赋值不影响既有值——领域校验先行）。
     */
    @Test
    @DisplayName("更新主体空名称拒绝")
    void updateInfo_blankNameRejected() {
        final Product product = product("测试商品", "旧描述", 2001L, 3001L);

        assertThatThrownBy(() -> product.updateInfo("  ", "新描述", 2002L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商品名称不能为空");
        assertThat(product.getName()).isEqualTo("测试商品");
        assertThat(product.getDescription()).isEqualTo("旧描述");
    }

    /**
     * happy：新增图片引用（默认非主图），加入序保持。
     */
    @Test
    @DisplayName("新增图片引用加入序保持且默认非主图")
    void addImage_keepsInsertionOrder() {
        final Product product = product("测试商品", null, null, null);

        product.addImage(image(11L, product.getId(), "/files/a.png", false));
        product.addImage(image(12L, product.getId(), "/files/b.png", false));

        final List<ProductImage> images = product.imagesOrdered();
        assertThat(images).hasSize(2);
        assertThat(images.get(0).getUrl()).isEqualTo("/files/a.png");
        assertThat(images.get(1).getUrl()).isEqualTo("/files/b.png");
        assertThat(images).noneMatch(ProductImage::isPrimary);
        assertThat(product.primaryImage()).isEmpty();
    }

    /**
     * happy：新增主图自动清除原主图标记（至多一条主图）。
     */
    @Test
    @DisplayName("新增主图替换原主图标记")
    void addImage_primaryReplacesExistingPrimary() {
        final Product product = product("测试商品", null, null, null);
        product.addImage(image(11L, product.getId(), "/files/a.png", true));
        product.addImage(image(12L, product.getId(), "/files/b.png", true));

        assertThat(product.primaryImage()).hasValueSatisfying(p -> p.getId().equals(12L));
        assertThat(product.getImageById(11L).orElseThrow().isPrimary()).isFalse();
    }

    /**
     * error：空 URL 图片引用拒绝；同一 ID 重复添加拒绝。
     */
    @Test
    @DisplayName("空URL与重复ID的图片引用拒绝")
    void addImage_invalidImageRejected() {
        final Product product = product("测试商品", null, null, null);

        assertThatThrownBy(() -> product.addImage(image(11L, product.getId(), " ", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片URL不能为空");
        product.addImage(image(11L, product.getId(), "/files/a.png", false));
        assertThatThrownBy(() -> product.addImage(image(11L, product.getId(), "/files/b.png", false)))
                .isInstanceOf(BusinessException.class);
        assertThat(product.imagesOrdered()).hasSize(1);
    }

    /**
     * happy：设置主图（清除原主图、目标设为主图）；对当前主图重复设置幂等。
     */
    @Test
    @DisplayName("设主图替换原主图且重复设置幂等")
    void setPrimaryImage_replacesAndIdempotent() {
        final Product product = product("测试商品", null, null, null);
        product.addImage(image(11L, product.getId(), "/files/a.png", false));
        product.addImage(image(12L, product.getId(), "/files/b.png", false));

        product.setPrimaryImage(11L);

        assertThat(product.primaryImage()).hasValueSatisfying(p -> p.getId().equals(11L));
        product.setPrimaryImage(11L);
        assertThat(product.primaryImage()).hasValueSatisfying(p -> p.getId().equals(11L));
    }

    /**
     * happy/error：删除主图后主图位清空；目标不存在拒绝。
     */
    @Test
    @DisplayName("删除主图后主图位清空且其余图片不动")
    void removeImage_clearsPrimarySlot() {
        final Product product = product("测试商品", null, null, null);
        product.addImage(image(11L, product.getId(), "/files/a.png", true));
        product.addImage(image(12L, product.getId(), "/files/b.png", false));

        product.removeImage(11L);

        assertThat(product.imagesOrdered()).extracting(ProductImage::getId).containsExactly(12L);
        assertThat(product.getImageById(12L).orElseThrow().isPrimary()).isFalse();
        assertThat(product.primaryImage()).isEmpty();
        assertThatThrownBy(() -> product.removeImage(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("图片不存在");
    }

    /**
     * happy：属性键值对增删改（值可空）。
     */
    @Test
    @DisplayName("属性增改删：键值整体替换且值可空")
    void attributeCrud_updatesKeyAndValue() {
        final Product product = product("测试商品", null, null, null);
        product.addAttribute(attribute(21L, product.getId(), "材质", "纯棉"));
        product.addAttribute(attribute(22L, product.getId(), "尺码", null));

        product.updateAttribute(21L, "面料", "棉100%");
        product.removeAttribute(22L);

        assertThat(product.attributesOrdered()).hasSize(1);
        final ProductAttribute updated = product.getAttributeById(21L).orElseThrow();
        assertThat(updated.getKey()).isEqualTo("面料");
        assertThat(updated.getValue()).isEqualTo("棉100%");
    }

    /**
     * error：属性键为空拒绝；键重复拒绝；目标不存在拒绝。
     */
    @Test
    @DisplayName("空键/重复键/不存在目标的属性操作拒绝")
    void attribute_invalidOperationsRejected() {
        final Product product = product("测试商品", null, null, null);
        product.addAttribute(attribute(21L, product.getId(), "材质", "纯棉"));

        assertThatThrownBy(() -> product.addAttribute(attribute(22L, product.getId(), " ", "x")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("属性键不能为空");
        assertThatThrownBy(() -> product.addAttribute(attribute(23L, product.getId(), "材质", "丝绸")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("属性键已存在");
        assertThatThrownBy(() -> product.updateAttribute(99L, "新键", "v"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("属性不存在");
        assertThatThrownBy(() -> product.removeAttribute(99L))
                .isInstanceOf(BusinessException.class);
        assertThat(product.attributesOrdered()).hasSize(1);
    }

    /**
     * critical：改键保持唯一（改到与其余属性冲突拒绝；改回自身键合法）。
     */
    @Test
    @DisplayName("改键与其余属性冲突拒绝而改回自身键合法")
    void updateAttribute_keyUniquenessExcludesSelf() {
        final Product product = product("测试商品", null, null, null);
        product.addAttribute(attribute(21L, product.getId(), "材质", "纯棉"));
        product.addAttribute(attribute(22L, product.getId(), "尺码", "L"));

        assertThatThrownBy(() -> product.updateAttribute(22L, "材质", "L"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("属性键已存在");
        product.updateAttribute(22L, "尺码", "XL");
        assertThat(product.getAttributeById(22L).orElseThrow().getValue()).isEqualTo("XL");
        product.updateAttribute(21L, "材质", "棉100%");
        assertThat(product.getAttributeById(21L).orElseThrow().getValue()).isEqualTo("棉100%");
    }

    /**
     * critical：集合快照为不可变副本（外部改引用列表不污染聚合；Ordered 保序）。
     *
     * @return 断言对象
     */
    @Test
    @DisplayName("集合快照不可变且保序")
    void orderedSnapshots_areImmutable() {
        final Product product = product("测试商品", null, null, null);
        product.addImage(image(11L, product.getId(), "/files/a.png", true));
        product.addImage(image(12L, product.getId(), "/files/b.png", false));

        final List<ProductImage> snapshot = product.imagesOrdered();

        assertThatThrownBy(() -> snapshot.add(image(13L, product.getId(), "/files/c.png", false)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(product.imagesOrdered()).hasSize(2);
        assertThat(Optional.ofNullable(product.primaryImage().orElseThrow().getId())).hasValue(11L);
    }

    /**
     * 构造测试用商品。
     *
     * @param name        商品名称
     * @param description 描述
     * @param categoryId  类目 ID
     * @param brandId     品牌 ID
     * @return 商品（ID 1001，店铺 1）
     */
    private static Product product(String name, String description, Long categoryId, Long brandId) {
        return new Product(1001L, 1L, name, description, categoryId, brandId, ProductStatus.DRAFT);
    }

    /**
     * 构造测试用图片引用。
     *
     * @param id        图片 ID
     * @param productId 商品 ID
     * @param url       图片 URL
     * @param primary   是否主图
     * @return 图片引用
     */
    private static ProductImage image(Long id, Long productId, String url, boolean primary) {
        return new ProductImage(id, productId, url, primary);
    }

    /**
     * 构造测试用属性。
     *
     * @param id        属性 ID
     * @param productId 商品 ID
     * @param key       属性键
     * @param value     属性值
     * @return 属性
     */
    private static ProductAttribute attribute(Long id, Long productId, String key, String value) {
        return new ProductAttribute(id, productId, key, value);
    }
}