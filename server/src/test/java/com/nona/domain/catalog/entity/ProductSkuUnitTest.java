package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Product 聚合规格模板 + 可售单元（SKU）集契约测试：模板整体替换与
 * SKU 集重建（按组合匹配保留价格/启用）、SKU 价格与启用独立维护、
 * 防爆炸上限守卫、同商品组合唯一（幂等配置归并）。
 * <p>
 * 覆盖：happy（配置模板生成 SKU 集、改价/启停、模板变更保留既有 SKU
 * 的价与启用、清除价格回未定价）、critical（空模板清空、单维度、组合
 * 移除/新增/存活语义、重复配置幂等、ID 唯一、上限边界恰 200）、
 * error（null 模板、超上限、非正价格、目标 SKU 不存在）。
 * <p>
 * 红状态说明：SKU 生成/重建、价格与启停维护均为契约声明（方法体抛
 * UnsupportedOperationException）——依赖这些行为的用例全部红，
 * 红因 = 实现缺失；装配形态（V1/V2 构造、Sku/装配读取）已实现为绿底座。
 */
class ProductSkuUnitTest {

    /**
     * SHA-256 十六进制形态（64 字符定长）
     */
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    // ---- Happy path ----

    /**
     * happy：配置模板生成完整 SKU 集——数量 = 组合数、顺序 = 模板展开序、
     * 每个 SKU 携带派生摘要（hash 定长 / summary 可读）、默认未定价且停用、
     * 归属本商品；模板整体就位。
     */
    @Test
    @DisplayName("配置模板生成 SKU 集且默认未定价停用")
    void configure_generatesSkuSet() {
        final Product product = draft();
        final SpecTemplate template = template(dim("颜色", "黑", "白"), dim("尺寸", "L", "XL"));

        product.configureSpecTemplate(template);

        assertThat(product.getSpecTemplate()).hasValueSatisfying(it -> {
            assertThat(it.isEmpty()).isFalse();
            assertThat(it.combinationCount()).isEqualTo(4);
        });
        final List<Sku> skus = product.skusOrdered();
        assertThat(skus).hasSize(4);
        assertThat(skus).extracting(Sku::getSpecSummary)
                .containsExactly(
                        "颜色:黑,尺寸:L",
                        "颜色:黑,尺寸:XL",
                        "颜色:白,尺寸:L",
                        "颜色:白,尺寸:XL");
        assertThat(skus).allSatisfy(sku -> {
            assertThat(sku.getSpecHash()).matches(HEX64);
            assertThat(sku.getProductId()).isEqualTo(1001L);
            assertThat(sku.getPrice()).isNull();
            assertThat(sku.isEnabled()).isFalse();
        });
        assertThat(skus).extracting(Sku::getId).doesNotHaveDuplicates();
    }

    /**
     * happy：改价生效（独立维护——每 SKU 各自定价）。
     */
    @Test
    @DisplayName("SKU 改价生效")
    void updateSkuPrice_applies() {
        final Product product = draftWithSkus(sku(11L, "h1", "颜色:黑", 100L, false));

        product.updateSkuPrice(11L, 2599L);

        assertThat(product.getSkuById(11L).orElseThrow().getPrice()).isEqualTo(2599L);
    }

    /**
     * happy：清除价格回未定价（未定价为草稿期合法形态）。
     */
    @Test
    @DisplayName("清除价格复位未定价")
    void updateSkuPrice_clearsToUnpriced() {
        final Product product = draftWithSkus(sku(11L, "h1", "颜色:黑", 100L, false));

        product.updateSkuPrice(11L, null);

        assertThat(product.getSkuById(11L).orElseThrow().getPrice()).isNull();
    }

    /**
     * happy：启停切换生效（独立维护）。
     */
    @Test
    @DisplayName("SKU 启停切换生效")
    void setSkuEnabled_toggles() {
        final Product product = draftWithSkus(sku(11L, "h1", "颜色:黑", null, false));

        product.setSkuEnabled(11L, true);
        assertThat(product.getSkuById(11L).orElseThrow().isEnabled()).isTrue();

        product.setSkuEnabled(11L, false);
        assertThat(product.getSkuById(11L).orElseThrow().isEnabled()).isFalse();
    }

    /**
     * happy（主链路闭环）：模板变更（新增规格值）后，同组合 SKU 存活——
     * 价格与启用保留、SKU ID 不变（跨域引用稳定）；新增组合生成默认态
     * 新 SKU；消失组合移除。
     */
    @Test
    @DisplayName("模板变更后同组合 SKU 价格启用与 ID 存活")
    void reconfigure_keepsPriceEnableAndIdentity() {
        final Product product = draft();
        product.configureSpecTemplate(template(dim("颜色", "黑", "白"), dim("尺寸", "L")));
        final long blackLId = product.skusOrdered().get(0).getId();
        product.updateSkuPrice(blackLId, 100L);
        product.setSkuEnabled(blackLId, true);

        product.configureSpecTemplate(
                template(dim("颜色", "黑", "白", "蓝"), dim("尺寸", "L")));

        assertThat(product.skusOrdered()).hasSize(3);
        final Sku survived = product.getSkuById(blackLId).orElseThrow();
        assertThat(survived.getPrice()).isEqualTo(100L);
        assertThat(survived.isEnabled()).isTrue();
        assertThat(survived.getSpecSummary()).isEqualTo("颜色:黑,尺寸:L");
        final Sku added = product.skusOrdered().stream()
                .filter(sku -> sku.getSpecSummary().equals("颜色:蓝,尺寸:L"))
                .findFirst().orElseThrow();
        assertThat(added.getPrice()).isNull();
        assertThat(added.isEnabled()).isFalse();
    }

    // ---- Critical path ----

    /**
     * critical：空模板配置清空 SKU 集（模板整体替换语义下置空即清集）；
     * 再配置模板可重新生成全新 SKU 集（旧 SKU 已回收，ID 全新）。
     */
    @Test
    @DisplayName("空模板清空 SKU 集且可重建")
    void configure_emptyTemplateClearsSkuSet() {
        final Product product = draft();
        product.configureSpecTemplate(template(dim("颜色", "黑", "白"), dim("尺寸", "L")));
        final List<Long> firstRoundIds = product.skusOrdered().stream().map(Sku::getId).toList();

        product.configureSpecTemplate(new SpecTemplate(null));

        assertThat(product.skusOrdered()).isEmpty();
        final SpecTemplate current = product.getSpecTemplate().orElseThrow();
        assertThat(current.isEmpty()).isTrue();
        assertThat(current.combinationCount()).isZero();

        product.configureSpecTemplate(template(dim("颜色", "黑", "白"), dim("尺寸", "L")));
        assertThat(product.skusOrdered()).hasSize(2);
        assertThat(product.skusOrdered()).extracting(Sku::getId)
                .doesNotContainAnyElementsOf(firstRoundIds);
    }

    /**
     * critical：单维度模板（1×N）展开 N 个 SKU。
     */
    @Test
    @DisplayName("单维度模板生成按值数展开的 SKU 集")
    void configure_singleDimensionExpandsByValueCount() {
        final Product product = draft();

        product.configureSpecTemplate(template(dim("颜色", "黑", "白", "蓝")));

        assertThat(product.skusOrdered()).hasSize(3);
        assertThat(product.skusOrdered()).extracting(Sku::getSpecSummary)
                .containsExactly("颜色:黑", "颜色:白", "颜色:蓝");
    }

    /**
     * critical：模板变更删除维度后，消失组合的 SKU 移除、存活组合价格
     * 保留且摘要刷新为当前模板派生值。
     */
    @Test
    @DisplayName("模板变更移除消失组合而存活组合价格保留")
    void reconfigure_dropsRemovedCombination() {
        final Product product = draft();
        product.configureSpecTemplate(template(dim("颜色", "黑", "白"), dim("尺寸", "L", "XL")));
        final long blackLId = product.skusOrdered().stream()
                .filter(sku -> sku.getSpecSummary().equals("颜色:黑,尺寸:L"))
                .findFirst().orElseThrow().getId();
        product.updateSkuPrice(blackLId, 100L);

        product.configureSpecTemplate(template(dim("颜色", "黑", "白")));

        assertThat(product.skusOrdered()).hasSize(2);
        assertThat(product.skusOrdered()).extracting(Sku::getSpecSummary)
                .containsExactlyInAnyOrder("颜色:黑", "颜色:白");
        final Sku survived = product.getSkuById(blackLId).orElseThrow();
        assertThat(survived.getPrice()).isEqualTo(100L);
        assertThat(survived.getSpecSummary()).isEqualTo("颜色:黑");
    }

    /**
     * critical：同一模板重复配置幂等——组合不重复生成（同组合归并），
     * SKU 数量/ID/价格/启用全部不变。
     */
    @Test
    @DisplayName("同一模板重复配置幂等不产生重复 SKU")
    void reconfigure_sameTemplateIdempotent() {
        final Product product = draft();
        product.configureSpecTemplate(template(dim("颜色", "黑", "白")));
        final long blackId = product.skusOrdered().get(0).getId();
        product.updateSkuPrice(blackId, 100L);

        product.configureSpecTemplate(template(dim("颜色", "黑", "白")));

        assertThat(product.skusOrdered()).hasSize(2);
        assertThat(product.skusOrdered()).extracting(Sku::getId)
                .doesNotHaveDuplicates();
        final Sku survived = product.getSkuById(blackId).orElseThrow();
        assertThat(survived.getPrice()).isEqualTo(100L);
    }

    /**
     * critical：分页边界——组合数恰为上限（200）允许配置。
     */
    @Test
    @DisplayName("组合数恰为上限允许配置")
    void configure_atMaxBoundaryAccepted() {
        final Product product = draft();
        final String[] values = new String[Product.MAX_SKU_COMBINATIONS];
        for (int i = 0; i < values.length; i++) {
            values[i] = "值" + i;
        }

        product.configureSpecTemplate(template(dim("颜色", values)));

        assertThat(product.skusOrdered()).hasSize(Product.MAX_SKU_COMBINATIONS);
    }

    /**
     * critical：SKU 定位——不属于本商品的 ID 按不存在呈现。
     */
    @Test
    @DisplayName("不存在的 SKU 按不存在呈现")
    void getSkuById_absentReturnsEmpty() {
        final Product product = draftWithSkus(sku(11L, "h1", "颜色:黑", null, false));

        assertThat(product.getSkuById(11L)).isPresent();
        assertThat(product.getSkuById(999L)).isEmpty();
    }

    // ---- Error path ----

    /**
     * error：null 模板拒绝（模板必填非空）。
     */
    @Test
    @DisplayName("null 模板配置拒绝")
    void configure_nullTemplateRejected() {
        final Product product = draft();

        assertThatThrownBy(() -> product.configureSpecTemplate(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("规格模板不能为空");
    }

    /**
     * error：组合数超上限拒绝（防组合爆炸防御；模板保持原值不被替换）。
     */
    @Test
    @DisplayName("组合数超上限拒绝且模板保持原值")
    void configure_exceedsMaxRejected() {
        final Product product = draft();
        final String[] values = new String[Product.MAX_SKU_COMBINATIONS + 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = "值" + i;
        }

        assertThatThrownBy(() -> product.configureSpecTemplate(template(dim("颜色", values))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("SKU数量超上限");
        assertThat(product.getSpecTemplate()).isEmpty();
    }

    /**
     * error：非正价格拒绝（0 与负数无业务意义；正整数分为合法形态）。
     */
    @Test
    @DisplayName("零价与负价拒绝")
    void updateSkuPrice_nonPositiveRejected() {
        final Product product = draftWithSkus(sku(11L, "h1", "颜色:黑", 100L, false));

        assertThatThrownBy(() -> product.updateSkuPrice(11L, 0L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("SKU价格必须为正整数");
        assertThatThrownBy(() -> product.updateSkuPrice(11L, -1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("SKU价格必须为正整数");
        assertThat(product.getSkuById(11L).orElseThrow().getPrice()).isEqualTo(100L);
    }

    /**
     * error：改价/启停目标 SKU 不存在拒绝（不属于本商品按不存在呈现）。
     */
    @Test
    @DisplayName("目标 SKU 不存在的改价与启停拒绝")
    void skuOperations_absentSkuRejected() {
        final Product product = draft();

        assertThatThrownBy(() -> product.updateSkuPrice(999L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("SKU不存在");
        assertThatThrownBy(() -> product.setSkuEnabled(999L, true))
                .isInstanceOf(BusinessException.class)
                .hasMessage("SKU不存在");
    }

    /**
     * 构造测试用空商品（V1 构造：无模板无 SKU）。
     *
     * @return 商品
     */
    private static Product draft() {
        return new Product(1001L, 1L, "测试商品", null, null, null, ProductStatus.DRAFT);
    }

    /**
     * 构造测试用商品（V2 构造：预置 SKU 集，无模板——装配底座，
     * 绕过模板生成路径，供改价/启停/定位契约独立验证）。
     *
     * @param skus 预置 SKU 集
     * @return 商品
     */
    private static Product draftWithSkus(Sku... skus) {
        return new Product(1001L, 1L, "测试商品", null, null, null,
                ProductStatus.DRAFT, null, Arrays.asList(skus));
    }

    /**
     * 构造规格维度。
     *
     * @param name   维度名
     * @param values 维度值
     * @return 维度
     */
    private static SpecItem dim(String name, String... values) {
        return new SpecItem(name, Arrays.asList(values));
    }

    /**
     * 构造规格模板。
     *
     * @param items 维度列表
     * @return 模板
     */
    private static SpecTemplate template(SpecItem... items) {
        return new SpecTemplate(Arrays.asList(items));
    }

    /**
     * 构造归属本商品的 SKU。
     *
     * @param id       SKU ID
     * @param hash     规格摘要
     * @param summary  可读摘要
     * @param price    价格（分，可空）
     * @param enabled  是否启用
     * @return SKU
     */
    private static Sku sku(long id, String hash, String summary, Long price, boolean enabled) {
        return new Sku(id, 1001L, hash, summary, price, enabled);
    }
}