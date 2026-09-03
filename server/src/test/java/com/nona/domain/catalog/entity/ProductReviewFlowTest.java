package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Product 聚合审核状态机契约测试（红阶段）：提交上架/审核通过/审核驳回
 * 三迁移 + 敏感字段编辑分流（stageSensitiveEdit）的全量守卫契约——
 * 状态机合法迁移、提交完整性校验逐项边界、待审期内容冻结、在售主图不
 * 变量、驳回可修改重提、已下架态无合法迁移。
 * <p>
 * 红状态说明：上述聚合新方法为设计契约（方法体抛
 * UnsupportedOperationException）——本文件全部用例红，红因 = 实现缺失；
 * 构造/子实体装载等装配底座（既有方法）为绿。
 *
 * @author nona9961
 */
class ProductReviewFlowTest {

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
     * happy：完整草稿提交 → 待审核（内容冻结语义入口）。
     */
    @Test
    @DisplayName("完整草稿提交转待审核")
    void submit_completeDraft_pendingReview() {
        final Product product = completeDraft();

        product.submitForReview();

        assertThat(product.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
    }

    /**
     * happy：在售商品敏感编辑分流（改价）→ 待审核；待审草稿位承载新内容、
     * 生效内容保持旧值；审批通过后生效内容被待审内容覆盖、待审位清空。
     */
    @Test
    @DisplayName("在售改价转待审且审批通过后新内容覆盖生效")
    void stageSensitiveEdit_thenApprove_coversEffectiveContent() {
        final Product product = onSaleComplete();
        final Sku sku = product.skusOrdered().get(0);
        final ProductContent effectiveBefore = effectiveContent(product);

        product.updateSkuPrice(sku.getId(), 999L);   // 敏感编辑：改价
        product.stageSensitiveEdit(effectiveBefore); // 分流：新内容入待审位、生效回退

        assertThat(product.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
        assertThat(product.hasPendingContent()).isTrue();
        assertThat(product.getSkuById(sku.getId()).orElseThrow().getPrice()).isEqualTo(1999L);
        assertThat(product.getPendingContent().orElseThrow().skus().get(0).getPrice())
                .isEqualTo(999L);

        product.approve();

        assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
        assertThat(product.hasPendingContent()).isFalse();
        assertThat(product.getSkuById(sku.getId()).orElseThrow().getPrice()).isEqualTo(999L);
    }

    /**
     * happy：驳回 → 回草稿 + 待审草稿作废（可修改后重提）。
     */
    @Test
    @DisplayName("驳回回草稿且待审草稿作废")
    void reject_returnsDraft_clearsPending() {
        final Product product = onSaleComplete();
        final ProductContent effectiveBefore = effectiveContent(product);
        product.updateSkuPrice(product.skusOrdered().get(0).getId(), 999L);
        product.stageSensitiveEdit(effectiveBefore);

        product.reject("图片侵权");

        assertThat(product.getStatus()).isEqualTo(ProductStatus.DRAFT);
        assertThat(product.hasPendingContent()).isFalse();
    }

    /**
     * happy：直提场景（无待审草稿）审批通过即生效。
     */
    @Test
    @DisplayName("直提商品审批通过即在售")
    void submitThenApprove_noPending_onSale() {
        final Product product = completeDraft();

        product.submitForReview();
        product.approve();

        assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
        assertThat(product.hasPendingContent()).isFalse();
    }

    /**
     * happy：驳回后修改重提——草稿态直改后再次提交转待审核。
     */
    @Test
    @DisplayName("驳回后修改重提再次提交转待审")
    void rejectedThenResubmit_pendingReview() {
        final Product product = completeDraft();
        product.submitForReview();
        product.reject("资质不足");
        product.updateInfo("整改后名称", "新描述", 7701L, 8801L);

        product.submitForReview();

        assertThat(product.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
        assertThat(product.getName()).isEqualTo("整改后名称");
    }

    // ---- Critical path ----

    /**
     * critical：提交完整性校验逐项边界——规格模板缺失（未配置/空模板）。
     */
    @Test
    @DisplayName("提交拒绝：未配置规格模板")
    void submit_noSpecTemplate_rejected() {
        final Product product = draft("无模板商品");
        product.addImage(image(11L, "/files/a.png", true));

        assertThatThrownBy(product::submitForReview)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_REQUIRED.code()));
    }

    /**
     * critical：提交完整性校验——空模板（0 维度，无 SKU）与未配置同拒绝。
     */
    @Test
    @DisplayName("提交拒绝：空规格模板")
    void submit_emptySpecTemplate_rejected() {
        final Product product = draft("空模板商品");
        product.addImage(image(11L, "/files/a.png", true));
        product.configureSpecTemplate(template());

        assertThatThrownBy(product::submitForReview)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_REQUIRED.code()));
    }

    /**
     * critical：提交完整性校验——模板已配置但 SKU 全部停用。
     */
    @Test
    @DisplayName("提交拒绝：无启用SKU")
    void submit_noEnabledSku_rejected() {
        final Product product = draft("全停用商品");
        product.addImage(image(11L, "/files/a.png", true));
        product.configureSpecTemplate(template(dim("颜色", "黑")));
        final Sku sku = product.skusOrdered().get(0);
        product.updateSkuPrice(sku.getId(), 1999L);

        assertThatThrownBy(product::submitForReview)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_SKU_ENABLED_REQUIRED.code()));
    }

    /**
     * critical：提交完整性校验——SKU 未定价（价格必须为正整数分）。
     */
    @Test
    @DisplayName("提交拒绝：SKU未定价")
    void submit_unpricedSku_rejected() {
        final Product product = draft("未定价商品");
        product.addImage(image(11L, "/files/a.png", true));
        product.configureSpecTemplate(template(dim("颜色", "黑")));
        final Sku sku = product.skusOrdered().get(0);
        product.setSkuEnabled(sku.getId(), true);

        assertThatThrownBy(product::submitForReview)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_SKU_PRICE_UNSET.code()));
    }

    /**
     * critical：提交完整性校验——缺主图（在售商品必须有主图）。
     */
    @Test
    @DisplayName("提交拒绝：缺主图")
    void submit_noPrimaryImage_rejected() {
        final Product product = draft("缺主图商品");
        product.configureSpecTemplate(template(dim("颜色", "黑")));
        final Sku sku = product.skusOrdered().get(0);
        product.updateSkuPrice(sku.getId(), 1999L);
        product.setSkuEnabled(sku.getId(), true);

        assertThatThrownBy(product::submitForReview)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_MAIN_IMAGE_REQUIRED.code()));
    }

    /**
     * critical：提交完整性校验——缺平台类目。
     */
    @Test
    @DisplayName("提交拒绝：缺平台类目")
    void submit_noCategory_rejected() {
        final Product product = draft("缺类目商品");
        product.addImage(image(11L, "/files/a.png", true));
        product.configureSpecTemplate(template(dim("颜色", "黑")));
        final Sku sku = product.skusOrdered().get(0);
        product.updateSkuPrice(sku.getId(), 1999L);
        product.setSkuEnabled(sku.getId(), true);

        assertThatThrownBy(product::submitForReview)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_CATEGORY_REQUIRED.code()));
    }

    /**
     * critical：提交完整性校验——缺品牌（类目已挂，仅剩品牌未挂）。
     */
    @Test
    @DisplayName("提交拒绝：缺品牌")
    void submit_noBrand_rejected() {
        final Product product = new Product(PRODUCT_ID, SHOP_ID, "缺品牌商品", "描述",
                7701L, null, ProductStatus.DRAFT);
        product.addImage(image(11L, "/files/a.png", true));
        product.configureSpecTemplate(template(dim("颜色", "黑")));
        final Sku sku = product.skusOrdered().get(0);
        product.updateSkuPrice(sku.getId(), 1999L);
        product.setSkuEnabled(sku.getId(), true);

        assertThatThrownBy(product::submitForReview)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_BRAND_REQUIRED.code()));
    }

    /**
     * critical：待审草稿（敏感字段编辑后）内容不完整拒绝——编辑清空类目
     * 后分流失败（待审内容必须满足在售齐备要求）。
     */
    @Test
    @DisplayName("敏感编辑后待审内容不完整拒绝")
    void stageSensitiveEdit_pendingIncomplete_rejected() {
        final Product product = onSaleComplete();
        final ProductContent effectiveBefore = effectiveContent(product);
        product.updateInfo("新名", "新描述", null, 8801L); // 编辑清空类目（敏感）

        assertThatThrownBy(() -> product.stageSensitiveEdit(effectiveBefore))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_CATEGORY_REQUIRED.code()));
    }

    /**
     * critical：待审期内编辑冻结——待审商品改主体拒绝（提交冻结内容）。
     */
    @Test
    @DisplayName("待审期内编辑冻结")
    void pendingReview_editingForbidden() {
        final Product product = completeDraft();
        product.submitForReview();

        assertThatThrownBy(() -> product.updateInfo("新名", "新描述", 7701L, 8801L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_EDIT_FORBIDDEN.code()));
    }

    /**
     * critical：待审期内改价同样冻结（写面统一守卫）。
     */
    @Test
    @DisplayName("待审期内改价冻结")
    void pendingReview_priceEditForbidden() {
        final Product product = completeDraft();
        product.submitForReview();

        assertThatThrownBy(() -> product.updateSkuPrice(product.skusOrdered().get(0).getId(), 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_EDIT_FORBIDDEN.code()));
    }

    /**
     * critical：在售状态删除唯一主图拒绝（在售商品必须有主图的状态不变量）。
     */
    @Test
    @DisplayName("在售商品删除唯一主图拒绝")
    void removePrimaryImage_onSale_rejected() {
        final Product product = onSaleComplete();
        final Long primaryImageId = product.primaryImage().orElseThrow().getId();

        assertThatThrownBy(() -> product.removeImage(primaryImageId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_MAIN_IMAGE_REQUIRED.code()));
    }

    /**
     * critical：审批期间生效内容不变——待审中（含待审草稿）正式内容保持
     * 旧值（买家继续可见旧版，直至平台裁定）。
     */
    @Test
    @DisplayName("审批期间生效内容保持旧版")
    void pendingReview_effectiveContentUnchanged() {
        final Product product = onSaleComplete();
        final ProductContent effectiveBefore = effectiveContent(product);
        final Sku sku = product.skusOrdered().get(0);
        final long oldPrice = sku.getPrice();
        product.updateSkuPrice(sku.getId(), 999L);
        product.stageSensitiveEdit(effectiveBefore);

        assertThat(product.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
        assertThat(product.getSkuById(sku.getId()).orElseThrow().getPrice()).isEqualTo(oldPrice);
        assertThat(product.getName()).isEqualTo("在售商品");
    }

    // ---- Error path ----

    /**
     * error：非法迁移守卫矩阵——草稿态直接审批/驳回、在售态提交/审批/驳回、
     * 待审态重复提交，全部拒绝。
     */
    @Test
    @DisplayName("非法状态迁移全部拒绝")
    void illegalTransitions_rejected() {
        final Product draft = completeDraft();
        assertThatThrownBy(draft::approve).satisfies(statusIllegal());
        assertThatThrownBy(() -> draft.reject("原因")).satisfies(statusIllegal());

        final Product onSale = onSaleComplete();
        assertThatThrownBy(onSale::submitForReview).satisfies(statusIllegal());
        assertThatThrownBy(onSale::approve).satisfies(statusIllegal());
        assertThatThrownBy(() -> onSale.reject("原因")).satisfies(statusIllegal());

        final Product pending = completeDraft();
        pending.submitForReview();
        assertThatThrownBy(pending::submitForReview).satisfies(statusIllegal());
    }

    /**
     * error：已下架态无任何合法迁移（下架端点属后续阶段，本期守卫完整）。
     */
    @Test
    @DisplayName("已下架状态拒绝全部状态机入口")
    void delisted_allTransitionsRejected() {
        final Product product = new Product(PRODUCT_ID, SHOP_ID, "已下架商品", "描述",
                7701L, 8801L, ProductStatus.DELISTED,
                template(dim("颜色", "黑")),
                List.of(sku(31L, "h", "颜色:黑", 1999L, true)));

        assertThatThrownBy(product::submitForReview).satisfies(statusIllegal());
        assertThatThrownBy(product::approve).satisfies(statusIllegal());
        assertThatThrownBy(() -> product.reject("原因")).satisfies(statusIllegal());
        assertThatThrownBy(() -> product.stageSensitiveEdit(effectiveContent(product)))
                .satisfies(statusIllegal());
    }

    /**
     * error：驳回原因必填（null/空白拒绝——无原因驳回商家无从修正）。
     */
    @Test
    @DisplayName("驳回原因必填")
    void reject_reasonBlank_rejected() {
        final Product product = completeDraft();
        product.submitForReview();

        assertThatThrownBy(() -> product.reject(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_REJECT_REASON_BLANK.code()));
        assertThatThrownBy(() -> product.reject("  "))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_REJECT_REASON_BLANK.code()));
    }

    /**
     * error：敏感字段分流仅限在售态——草稿/待审/已下架态入场拒绝。
     */
    @Test
    @DisplayName("敏感编辑分流仅限在售状态")
    void stageSensitiveEdit_nonOnSale_rejected() {
        final Product draft = completeDraft();
        assertThatThrownBy(() -> draft.stageSensitiveEdit(effectiveContent(draft)))
                .satisfies(statusIllegal());
        final Product pending = completeDraft();
        pending.submitForReview();
        assertThatThrownBy(() -> pending.stageSensitiveEdit(effectiveContent(pending)))
                .satisfies(statusIllegal());
    }

    /**
     * error：草稿态提交时待审草稿位必须为空（异常形态防御）——直提场景
     * 携带待审草稿拒绝。
     */
    @Test
    @DisplayName("草稿态携带待审草稿提交拒绝")
    void submit_withPendingContent_rejected() {
        final Product product = completeDraft();
        product.submitForReview();
        product.reject("原因");
        product.restorePendingContent(effectiveContent(product));

        assertThatThrownBy(product::submitForReview).satisfies(statusIllegal());
    }

    // ---- 装配底座断言（本文件红因参照）----

    /**
     * 装配：无待审草稿时访问器返回空（既有装配底座）。
     */
    @Test
    @DisplayName("无待审草稿访问器返回空")
    void pendingAccessors_emptyByDefault() {
        final Product product = completeDraft();

        assertThat(product.hasPendingContent()).isFalse();
        assertThat(product.getPendingContent()).isEmpty();
    }

    /**
     * 状态断言辅助（非法迁移统一业务码）。
     *
     * @return 异常断言断言器（BusinessException + 状态非法码）
     */
    private static java.util.function.Consumer<Throwable> statusIllegal() {
        return e -> {
            assertThat(e).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) e).getBusinessCode())
                    .isEqualTo(EcommerceBusinessCode.CATALOG_PRODUCT_STATUS_ILLEGAL.code());
        };
    }

    // ---- 构建辅助 ----

    /**
     * 构造草稿（仅主体）。
     *
     * @param name 商品名称
     * @return 商品聚合
     */
    private static Product draft(String name) {
        return new Product(PRODUCT_ID, SHOP_ID, name, "描述", null, null, ProductStatus.DRAFT);
    }

    /**
     * 构造完整草稿：名称/描述/类目/品牌 + 主图 + 属性 + 两 SKU（定价启用）。
     *
     * @return 完整草稿
     */
    private static Product completeDraft() {
        final Product product = new Product(PRODUCT_ID, SHOP_ID, "完整商品", "描述",
                7701L, 8801L, ProductStatus.DRAFT);
        product.addImage(image(11L, "/files/a.png", true));
        product.addAttribute(attribute(21L, "材质", "纯棉"));
        product.configureSpecTemplate(template(dim("颜色", "黑", "白")));
        final Sku first = product.skusOrdered().get(0);
        final Sku second = product.skusOrdered().get(1);
        product.updateSkuPrice(first.getId(), 1999L);
        product.updateSkuPrice(second.getId(), 2999L);
        product.setSkuEnabled(first.getId(), true);
        product.setSkuEnabled(second.getId(), true);
        return product;
    }

    /**
     * 构造在售完整商品（与 {@link #completeDraft()} 同形态，状态 ON_SALE）。
     *
     * @return 在售完整商品
     */
    private static Product onSaleComplete() {
        final Product product = new Product(PRODUCT_ID, SHOP_ID, "在售商品", "描述",
                7701L, 8801L, ProductStatus.ON_SALE);
        product.addImage(image(11L, "/files/a.png", true));
        product.configureSpecTemplate(template(dim("颜色", "黑", "白")));
        final Sku first = product.skusOrdered().get(0);
        final Sku second = product.skusOrdered().get(1);
        product.updateSkuPrice(first.getId(), 1999L);
        product.updateSkuPrice(second.getId(), 2999L);
        product.setSkuEnabled(first.getId(), true);
        product.setSkuEnabled(second.getId(), true);
        return product;
    }

    /**
     * 导出当前聚合全量为内容载体（改价/编辑前快照用）：子实体以新实例
     * 深拷贝承载——内容载体与聚合完全解耦（编辑突变聚合子实体不影响
     * 已导出的生效内容快照；聚合装载路径以 ID 重建实例，身份在、值独立）。
     *
     * @param product 商品聚合
     * @return 内容载体
     */
    private static ProductContent effectiveContent(Product product) {
        return new ProductContent(
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                product.getBrandId(),
                product.imagesOrdered().stream()
                        .map(image -> new ProductImage(
                                image.getId(), image.getProductId(), image.getUrl(), image.isPrimary()))
                        .toList(),
                product.attributesOrdered().stream()
                        .map(attribute -> new ProductAttribute(
                                attribute.getId(), attribute.getProductId(),
                                attribute.getKey(), attribute.getValue()))
                        .toList(),
                product.getSpecTemplate().orElse(null),
                product.skusOrdered().stream()
                        .map(sku -> new Sku(
                                sku.getId(), sku.getProductId(), sku.getSpecHash(),
                                sku.getSpecSummary(), sku.getPrice(), sku.isEnabled()))
                        .toList());
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
     * @param id      SKU ID
     * @param hash    组合摘要
     * @param summary 可读摘要
     * @param price   价格（分）
     * @param enabled 是否启用
     * @return SKU
     */
    private static Sku sku(long id, String hash, String summary, Long price, boolean enabled) {
        return new Sku(id, PRODUCT_ID, hash, summary, price, enabled);
    }

    /**
     * 构造空模板（0 维度）。
     *
     * @return 空模板
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