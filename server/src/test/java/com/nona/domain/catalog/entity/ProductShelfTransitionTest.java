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
 * Product 聚合上下架状态机契约测试（WU-19）：手动下架（S4.5 ①
 * ON_SALE → DELISTED）+ 手动重新上架（DELISTED → ON_SALE 回迁）——迁移
 * 守卫收敛在聚合方法（delist/relist），断言覆盖状态机全迁移矩阵
 * （happy/非法迁移拒绝/完整性防御/往返闭环）。
 * <p>
 * 既有审核流断言（submit/approve/reject 对已下架态仍全拒绝）不受影响：
 * delist/relist 为新增迁移面，不改变既有状态机入口语义。
 *
 * @author nona9961
 */
class ProductShelfTransitionTest {

    private static final long PRODUCT_ID = 1001L;
    private static final long SHOP_ID = 9101L;

    // ---- Happy path ----

    /**
     * happy：在售商品手动下架 → 已下架（状态迁移生效）。
     */
    @Test
    @DisplayName("在售商品手动下架成功")
    void delist_onSale_delisted() {
        final Product product = onSaleComplete();

        product.delist();

        assertThat(product.getStatus()).isEqualTo(ProductStatus.DELISTED);
    }

    /**
     * happy：已下架商品手动重新上架 → 在售（「在售 ⇄ 已下架」回迁，免重审）。
     */
    @Test
    @DisplayName("已下架商品重新上架成功")
    void relist_delisted_onSale() {
        final Product product = delistedComplete();

        product.relist();

        assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }

    // ---- Critical path ----

    /**
     * critical：重复下架拒绝（已下架态无重复下架语义——非法迁移）。
     */
    @Test
    @DisplayName("已下架商品重复下架拒绝")
    void delist_twice_rejected() {
        final Product product = delistedComplete();

        assertThatThrownBy(product::delist).satisfies(statusIllegal());
    }

    /**
     * critical：重新上架的完整性防御校验——内容不完整（缺主图）的已下架
     * 商品拒绝回在售（在售不变量：主图/类目/品牌齐备——脏形态防御兜底）。
     */
    @Test
    @DisplayName("内容不完整的已下架商品拒绝上架")
    void relist_incomplete_rejected() {
        final Product product = new Product(PRODUCT_ID, SHOP_ID, "不完整下架商品", "描述",
                7701L, 8801L, ProductStatus.DELISTED);

        assertThatThrownBy(product::relist).satisfies(completenessRejected());
    }

    /**
     * critical：在售 ⇄ 已下架往返闭环——下架后上架、再下架均合法（生命周期
     * 可逆对，除草稿/待审单向路径外唯一可逆迁移）。
     */
    @Test
    @DisplayName("下架上架往返闭环")
    void delistRelist_roundTrip() {
        final Product product = onSaleComplete();
        product.delist();
        product.relist();

        assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);

        product.delist();
        assertThat(product.getStatus()).isEqualTo(ProductStatus.DELISTED);
    }

    // ---- Error path ----

    /**
     * error：草稿态下架拒绝（未生效内容无下架语义——非法迁移）。
     */
    @Test
    @DisplayName("草稿商品下架拒绝")
    void delist_draft_rejected() {
        final Product product = completeDraft();

        assertThatThrownBy(product::delist).satisfies(statusIllegal());
    }

    /**
     * error：待审核态下架拒绝（待审内容冻结待裁定，无下架入口——非法迁移）。
     */
    @Test
    @DisplayName("待审核商品下架拒绝")
    void delist_pending_rejected() {
        final Product product = completeDraft();
        product.submitForReview();

        assertThatThrownBy(product::delist).satisfies(statusIllegal());
    }

    /**
     * error：待审核态重新上架拒绝（上架迁移仅限已下架态回迁）。
     */
    @Test
    @DisplayName("待审核商品重新上架拒绝")
    void relist_pending_rejected() {
        final Product product = completeDraft();
        product.submitForReview();

        assertThatThrownBy(product::relist).satisfies(statusIllegal());
    }

    /**
     * error：在售态重新上架拒绝（在售即上架态，重复上架无业务意义）。
     */
    @Test
    @DisplayName("在售商品重新上架拒绝")
    void relist_onSale_rejected() {
        final Product product = onSaleComplete();

        assertThatThrownBy(product::relist).satisfies(statusIllegal());
    }

    // ---- 装配底座断言 ----

    /**
     * 装配：下架不改变生效内容（主体/图片/属性/SKU 保持原值——下架为
     * 生命周期迁移非内容变更）。
     */
    @Test
    @DisplayName("下架不动生效内容")
    void delist_contentUnchanged() {
        final Product product = onSaleComplete();

        assertThat(product.getName()).isEqualTo("在售商品");
        assertThat(product.imagesOrdered()).hasSize(1);
        assertThat(product.skusOrdered()).hasSize(1);
    }

    /**
     * 状态非法断言辅助（非法迁移统一业务码）。
     *
     * @return 异常断言器（BusinessException + 状态非法码）
     */
    private static Consumer<Throwable> statusIllegal() {
        return e -> {
            assertThat(e).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_STATUS_ILLEGAL.code());
        };
    }

    /**
     * 完整性拒绝断言辅助（重新上架防御校验：逐项细化码——主图缺失码）。
     *
     * @return 异常断言器（BusinessException + 主图必填码）
     */
    private static Consumer<Throwable> completenessRejected() {
        return e -> {
            assertThat(e).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_MAIN_IMAGE_REQUIRED.code());
        };
    }

    // ---- 构建辅助（对齐 ProductReviewFlowTest 同形制）----

    /**
     * 构造在售完整商品（两 SKU 定价启用 + 主图 + 属性，状态 ON_SALE）。
     *
     * @return 在售完整商品
     */
    private static Product onSaleComplete() {
        final Product product = new Product(PRODUCT_ID, SHOP_ID, "在售商品", "描述",
                7701L, 8801L, ProductStatus.ON_SALE);
        product.addImage(image(11L, "/files/a.png", true));
        product.addAttribute(attribute(21L, "材质", "纯棉"));
        product.configureSpecTemplate(template());
        priceAndEnableAll(product);
        return product;
    }

    /**
     * 构造已下架完整商品（与在售商品同内容形态，状态 DELISTED——从
     * ON_SALE 迁移的合法形态：内容完整、曾审核通过；下架态写面冻结，
     * 装配走装载路径：构造器装模板/SKU + restore 装图片/属性）。
     *
     * @return 已下架完整商品
     */
    private static Product delistedComplete() {
        final Product product = new Product(PRODUCT_ID, SHOP_ID, "已下架商品", "描述",
                7701L, 8801L, ProductStatus.DELISTED,
                new SpecTemplate(List.of(new SpecItem("颜色", List.of("黑")))),
                List.of(new Sku(9001L, PRODUCT_ID, "hash-delisted", "颜色:黑", 1999L, true)));
        product.restoreImage(new ProductImage(11L, PRODUCT_ID, "/files/a.png", true));
        product.restoreAttribute(new ProductAttribute(21L, PRODUCT_ID, "材质", "纯棉"));
        return product;
    }

    /**
     * 构造完整草稿（下架/上架非法迁移矩阵用：草稿/待审态商品形态）。
     *
     * @return 完整草稿
     */
    private static Product completeDraft() {
        final Product product = new Product(PRODUCT_ID, SHOP_ID, "完整商品", "描述",
                7701L, 8801L, ProductStatus.DRAFT);
        product.addImage(image(11L, "/files/a.png", true));
        product.addAttribute(attribute(21L, "材质", "纯棉"));
        product.configureSpecTemplate(template());
        priceAndEnableAll(product);
        return product;
    }

    /**
     * 全部 SKU 定价并启用（提交上架/在售形态齐备辅助）。
     *
     * @param product 商品聚合
     */
    private static void priceAndEnableAll(Product product) {
        for (final Sku sku : product.skusOrdered()) {
            product.updateSkuPrice(sku.getId(), 1999L);
            product.setSkuEnabled(sku.getId(), true);
        }
    }

    /**
     * 单规格模板（一维一值 → 单 SKU 组合；结构合法）。
     *
     * @return 规格模板
     */
    private static SpecTemplate template() {
        return new SpecTemplate(List.of(new SpecItem("颜色", List.of("黑"))));
    }

    /**
     * 构造图片引用。
     *
     * @param id      图片 ID
     * @param url     URL
     * @param primary 是否主图
     * @return 图片引用
     */
    private static ProductImage image(long id, String url, boolean primary) {
        return new ProductImage(id, PRODUCT_ID, url, primary);
    }

    /**
     * 构造自定义属性。
     *
     * @param id    属性 ID
     * @param key   属性键
     * @param value 属性值
     * @return 属性
     */
    private static ProductAttribute attribute(long id, String key, String value) {
        return new ProductAttribute(id, PRODUCT_ID, key, value);
    }
}
