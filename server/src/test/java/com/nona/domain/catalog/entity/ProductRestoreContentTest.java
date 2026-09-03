package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Product 聚合整体内容重置（回滚路径唯一内容入口）契约测试：
 * restoreContent 以内容载体整体替换当前商品内容——主体、图片引用、
 * 自定义属性、规格模板与 SKU 集（含 SKU 身份/价格/启用状态恢复）。
 * <p>
 * 覆盖：happy（全量内容替换 + SKU 集身份与价格/启用恢复 + 模板就位）、
 * critical（空内容恢复——仅名称/集合空/模板未配置；模板 null 清空模板
 * 与 SKU 集）、error（名称空、模板组合超上限、图片 URL 空等守卫拒绝——
 * 回滚内容同样受聚合不变量约束）。
 * <p>
 * 红状态说明：restoreContent 为契约声明（方法体抛
 * UnsupportedOperationException）——依赖该行为的用例全部红，红因 =
 * 实现缺失；装配形态（构造/快照载体/子实体）已实现为绿底座。
 */
class ProductRestoreContentTest {

    /**
     * 测试商品 ID
     */
    private static final long PRODUCT_ID = 1001L;

    /**
     * 测试店铺 ID（租户锚点）
     */
    private static final long SHOP_ID = 9101L;

    // ---- Happy path ----

    /**
     * happy：整体内容替换生效——主体字段落位、图片/属性集合整体替换
     * （含主图标记与属性键值）、规格模板与 SKU 集就位（SKU 身份/价格/
     * 启用状态随快照恢复，不参与组合匹配存活语义）。
     */
    @Test
    @DisplayName("整体内容替换生效且SKU身份价格启用恢复")
    void restoreContent_replacesWholeContent() {
        final Product product = draft("当前名");
        product.addImage(image(11L, "/files/old.png", true));
        product.addAttribute(attribute(21L, "旧键", "旧值"));
        product.configureSpecTemplate(template());

        final ProductContent content = new ProductContent(
                "历史名", "历史描述", 7701L, 8801L,
                List.of(image(12L, "/files/a.png", true), image(13L, "/files/b.png", false)),
                List.of(attribute(22L, "材质", "纯棉")),
                template(dim("颜色", "黑")),
                List.of(sku(31L, "h1", "颜色:黑", 1999L, true)));

        product.restoreContent(content);

        assertThat(product.getName()).isEqualTo("历史名");
        assertThat(product.getDescription()).isEqualTo("历史描述");
        assertThat(product.getCategoryId()).isEqualTo(7701L);
        assertThat(product.getBrandId()).isEqualTo(8801L);
        assertThat(product.imagesOrdered()).extracting(ProductImage::getId)
                .containsExactly(12L, 13L);
        assertThat(product.imagesOrdered().get(0).isPrimary()).isTrue();
        assertThat(product.imagesOrdered().get(1).isPrimary()).isFalse();
        assertThat(product.attributesOrdered()).extracting(ProductAttribute::getKey)
                .containsExactly("材质");
        assertThat(product.attributesOrdered().get(0).getValue()).isEqualTo("纯棉");
        assertThat(product.getSpecTemplate()).isPresent();
        assertThat(product.getSpecTemplate().orElseThrow().combinationCount()).isEqualTo(1);
        assertThat(product.skusOrdered()).extracting(Sku::getId).containsExactly(31L);
        assertThat(product.skusOrdered().get(0).getSpecHash()).isEqualTo("h1");
        assertThat(product.skusOrdered().get(0).getPrice()).isEqualTo(1999L);
        assertThat(product.skusOrdered().get(0).isEnabled()).isTrue();
    }

    // ---- Critical path ----

    /**
     * critical：空内容快照恢复——仅名称就位，图片/属性/SKU 为空、模板
     * 未配置（草稿只有名称的合法历史形态）。
     */
    @Test
    @DisplayName("空内容快照恢复为仅名称草稿形态")
    void restoreContent_emptySnapshot() {
        final Product product = draft("当前名");
        product.addImage(image(14L, "/files/x.png", true));

        product.restoreContent(new ProductContent(
                "空快照名", null, null, null,
                List.of(), List.of(), null, List.of()));

        assertThat(product.getName()).isEqualTo("空快照名");
        assertThat(product.getDescription()).isNull();
        assertThat(product.getCategoryId()).isNull();
        assertThat(product.getBrandId()).isNull();
        assertThat(product.imagesOrdered()).isEmpty();
        assertThat(product.attributesOrdered()).isEmpty();
        assertThat(product.getSpecTemplate()).isEmpty();
        assertThat(product.skusOrdered()).isEmpty();
    }

    /**
     * critical：模板 null 恢复清空模板与 SKU 集（历史上未配置模板的
     * 快照回滚后保持该形态）。
     */
    @Test
    @DisplayName("模板null恢复清空模板与SKU集")
    void restoreContent_nullTemplateClearsSpecAndSkus() {
        final Product product = draft("当前名");
        product.configureSpecTemplate(template(dim("颜色", "黑")));

        product.restoreContent(new ProductContent(
                "当前名", null, null, null,
                List.of(), List.of(), null, List.of()));

        assertThat(product.getSpecTemplate()).isEmpty();
        assertThat(product.skusOrdered()).isEmpty();
    }

    // ---- Error path ----

    /**
     * error：名称空白的内容载体拒绝（不变量 1 在回滚路径同样成立——
     * 历史快照不可能含空名称，防御性拒绝）。
     */
    @Test
    @DisplayName("空白名称的恢复拒绝")
    void restoreContent_blankNameRejected() {
        final Product product = draft("当前名");

        assertThatThrownBy(() -> product.restoreContent(new ProductContent(
                " ", null, null, null,
                List.of(), List.of(), null, List.of())))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_NAME_BLANK.code()));
    }

    /**
     * error：模板组合数超上限的恢复拒绝（防爆炸不变量在回滚路径同样
     * 成立——非法快照数据防御）。
     */
    @Test
    @DisplayName("组合数超上限的恢复拒绝")
    void restoreContent_combinationBurstRejected() {
        final Product product = draft("当前名");
        final SpecTemplate burst = new SpecTemplate(List.of(
                dim("一", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10"),
                dim("二", "b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8", "b9", "b10"),
                dim("三", "c1", "c2", "c3")));

        assertThatThrownBy(() -> product.restoreContent(new ProductContent(
                "当前名", null, null, null,
                List.of(), List.of(), burst, List.of())))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_SKU_COUNT_EXCEEDED.code()));
    }

    /**
     * error：图片 URL 空白的恢复拒绝（图片引用必填不变量在回滚路径
     * 同样成立）。
     */
    @Test
    @DisplayName("空白图片URL的恢复拒绝")
    void restoreContent_blankImageUrlRejected() {
        final Product product = draft("当前名");

        assertThatThrownBy(() -> product.restoreContent(new ProductContent(
                "当前名", null, null, null,
                List.of(image(15L, " ", false)), List.of(), null, List.of())))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_IMAGE_URL_BLANK.code()));
    }

    /**
     * 构造测试商品（仅主体）。
     *
     * @param name 商品名称
     * @return 商品聚合
     */
    private static Product draft(String name) {
        return new Product(PRODUCT_ID, SHOP_ID, name, "描述", null, null, ProductStatus.DRAFT);
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

    /**
     * 构造 SKU。
     *
     * @param id          SKU ID
     * @param hash        组合摘要
     * @param summary     可读摘要
     * @param price       价格（分）
     * @param enabled     是否启用
     * @return SKU
     */
    private static Sku sku(long id, String hash, String summary, Long price, boolean enabled) {
        return new Sku(id, PRODUCT_ID, hash, summary, price, enabled);
    }

    /**
     * 构造空模板。
     *
     * @return 空模板（0 维度）
     */
    private static SpecTemplate template() {
        return new SpecTemplate(List.of());
    }

    /**
     * 构造单维度模板。
     *
     * @param dimension 维度
     * @return 模板
     */
    private static SpecTemplate template(SpecItem dimension) {
        return new SpecTemplate(List.of(dimension));
    }

    /**
     * 构造维度。
     *
     * @param name   维度名
     * @param values 维度值
     * @return 维度
     */
    private static SpecItem dim(String name, String... values) {
        return new SpecItem(name, List.of(values));
    }
}