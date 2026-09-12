package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Product 聚合店铺分类绑定/运费模板绑定契约测试：商品-店铺分类
 * 多对多（整体替换 + 从表行装载）+ 商品运费模板绑定。
 * 归属校验（分类/模板属于商品店铺）在用例层（跨聚合读
 * Shop/FreightTemplate），本文件只覆盖聚合内守卫与集合语义。
 *
 * @author nona9961
 */
class ProductShopCategoryBindingUnitTest {

    private static final long PRODUCT_ID = 1001L;
    private static final long SHOP_ID = 9101L;

    // ---- Happy path ----

    /**
     * happy：整体替换绑定两个店铺分类 → 绑定集按提交序可读（回显契约）。
     */
    @Test
    @DisplayName("整体替换绑定多个店铺分类")
    void replaceShopCategories_bindsInOrder() {
        final Product product = onSaleProduct();

        product.replaceShopCategories(List.of(7701L, 7702L));

        assertThat(product.shopCategoryIdsOrdered()).containsExactly(7701L, 7702L);
        assertThat(product.shopCategoryRefsOrdered()).hasSize(2);
    }

    /**
     * happy：绑定空集合（null 按空集）= 清空全部绑定（多选全取消语义）。
     */
    @Test
    @DisplayName("空集合清空全部绑定")
    void replaceShopCategories_emptyClears() {
        final Product product = onSaleProduct();
        product.replaceShopCategories(List.of(7701L, 7702L));

        product.replaceShopCategories(List.of());
        assertThat(product.shopCategoryIdsOrdered()).isEmpty();

        product.replaceShopCategories(List.of(7701L));
        product.replaceShopCategories(null);
        assertThat(product.shopCategoryIdsOrdered()).isEmpty();
    }

    /**
     * happy：整体替换收敛——再次提交差集后绑定集 = 提交集（表单保存幂等）。
     */
    @Test
    @DisplayName("再次整体替换收敛为提交集")
    void replaceShopCategories_replaceConverges() {
        final Product product = onSaleProduct();
        product.replaceShopCategories(List.of(7701L, 7702L, 7703L));

        product.replaceShopCategories(List.of(7702L));

        assertThat(product.shopCategoryIdsOrdered()).containsExactly(7702L);
    }

    /**
     * happy：从表行装载路径（仓储装配）——装载后绑定集可读，不触发
     * 写面冻结守卫（待审核/在售商品装配需要装载从表行）。
     */
    @Test
    @DisplayName("装载绑定行后可读")
    void restoreShopCategoryRef_loaded() {
        final Product product = onSaleProduct();

        product.restoreShopCategoryRef(new ProductShopCategoryRef(8801L, PRODUCT_ID, 7701L));
        product.restoreShopCategoryRef(new ProductShopCategoryRef(8802L, PRODUCT_ID, 7702L));

        assertThat(product.shopCategoryIdsOrdered()).containsExactly(7701L, 7702L);
    }

    /**
     * happy：绑定运费模板 → 模板 ID 可读（商品级模板引用承载）。
     */
    @Test
    @DisplayName("绑定运费模板")
    void bindFreightTemplate_bound() {
        final Product product = onSaleProduct();

        product.bindFreightTemplate(9901L);

        assertThat(product.getFreightTemplateId()).isEqualTo(9901L);
    }

    /**
     * happy：解绑运费模板（null = 清空引用——未绑定合法形态）。
     */
    @Test
    @DisplayName("解绑运费模板")
    void bindFreightTemplate_unbind() {
        final Product product = onSaleProduct();
        product.bindFreightTemplate(9901L);

        product.bindFreightTemplate(null);

        assertThat(product.getFreightTemplateId()).isNull();
    }

    // ---- Critical path ----

    /**
     * critical：重复分类绑定去重（同一分类重复绑定无业务意义——rel 行
     * (product_id, shop_category_id) 唯一约束的聚合侧防线，静默去重）。
     */
    @Test
    @DisplayName("重复分类绑定去重")
    void replaceShopCategories_duplicatesDeduped() {
        final Product product = onSaleProduct();

        product.replaceShopCategories(List.of(7701L, 7701L, 7702L));

        assertThat(product.shopCategoryIdsOrdered()).containsExactly(7701L, 7702L);
    }

    /**
     * critical：待审核期绑定拒绝（内容冻结——店铺分类属商品编辑面，
     * 驳回后可改）。
     */
    @Test
    @DisplayName("待审核商品绑定分类拒绝")
    void replaceShopCategories_pendingRejected() {
        final Product product = completeDraft();
        product.submitForReview();

        assertThatThrownBy(() -> product.replaceShopCategories(List.of(7701L)))
                .satisfies(editForbidden());
    }

    /**
     * critical：已下架期绑定拒绝（已下架态无编辑路径）。
     */
    @Test
    @DisplayName("已下架商品绑定分类拒绝")
    void replaceShopCategories_delistedRejected() {
        final Product product = delistedProduct();

        assertThatThrownBy(() -> product.replaceShopCategories(List.of(7701L)))
                .satisfies(statusIllegal());
    }

    /**
     * critical：待审核期绑定运费模板拒绝（写面冻结同族）。
     */
    @Test
    @DisplayName("待审核商品绑定运费模板拒绝")
    void bindFreightTemplate_pendingRejected() {
        final Product product = completeDraft();
        product.submitForReview();

        assertThatThrownBy(() -> product.bindFreightTemplate(9901L))
                .satisfies(editForbidden());
    }

    /**
     * critical：已下架期绑定运费模板拒绝。
     */
    @Test
    @DisplayName("已下架商品绑定运费模板拒绝")
    void bindFreightTemplate_delistedRejected() {
        final Product product = delistedProduct();

        assertThatThrownBy(() -> product.bindFreightTemplate(9901L))
                .satisfies(statusIllegal());
    }

    // ---- 装配底座断言 ----

    /**
     * 装配：默认无绑定、无模板引用（构造即空——引用为可空运营关系）。
     */
    @Test
    @DisplayName("默认无分类绑定与模板引用")
    void defaults_empty() {
        final Product product = onSaleProduct();

        assertThat(product.shopCategoryIdsOrdered()).isEmpty();
        assertThat(product.shopCategoryRefsOrdered()).isEmpty();
        assertThat(product.getFreightTemplateId()).isNull();
    }

    /**
     * 待审冻结断言辅助（统一业务码：待审核期写面冻结）。
     *
     * @return 异常断言器
     */
    private static Consumer<Throwable> editForbidden() {
        return e -> {
            assertThat(e).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_EDIT_FORBIDDEN.code());
        };
    }

    /**
     * 状态非法断言辅助（已下架态非法迁移统一码）。
     *
     * @return 异常断言器
     */
    private static Consumer<Throwable> statusIllegal() {
        return e -> {
            assertThat(e).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_STATUS_ILLEGAL.code());
        };
    }

    // ---- 构建辅助 ----

    /**
     * 构造在售商品（模板 + 单 SKU 定价启用；绑定写面在 ON_SALE 放行）。
     *
     * @return 在售商品
     */
    private static Product onSaleProduct() {
        final Product product = new Product(PRODUCT_ID, SHOP_ID, "在售商品", "描述",
                7701L, 8801L, ProductStatus.ON_SALE);
        product.configureSpecTemplate(template());
        final Sku sku = product.skusOrdered().get(0);
        product.updateSkuPrice(sku.getId(), 1999L);
        product.setSkuEnabled(sku.getId(), true);
        return product;
    }

    /**
     * 构造完整草稿（待审核态守卫测试用：提交后转待审核）。
     *
     * @return 完整草稿
     */
    private static Product completeDraft() {
        final Product product = new Product(PRODUCT_ID, SHOP_ID, "完整商品", "描述",
                7701L, 8801L, ProductStatus.DRAFT);
        product.addImage(new ProductImage(11L, PRODUCT_ID, "/files/a.png", true));
        product.configureSpecTemplate(template());
        final Sku sku = product.skusOrdered().get(0);
        product.updateSkuPrice(sku.getId(), 1999L);
        product.setSkuEnabled(sku.getId(), true);
        return product;
    }

    /**
     * 构造已下架商品（守卫测试用——构造器装载形态，写面冻结）。
     *
     * @return 已下架商品
     */
    private static Product delistedProduct() {
        return new Product(PRODUCT_ID, SHOP_ID, "已下架商品", "描述",
                7701L, 8801L, ProductStatus.DELISTED,
                new SpecTemplate(List.of(new SpecItem("颜色", List.of("黑")))),
                List.of(new Sku(9001L, PRODUCT_ID, "hash-d", "颜色:黑", 1999L, true)));
    }

    /**
     * 单规格模板。
     *
     * @return 规格模板
     */
    private static SpecTemplate template() {
        return new SpecTemplate(List.of(new SpecItem("颜色", List.of("黑"))));
    }
}
