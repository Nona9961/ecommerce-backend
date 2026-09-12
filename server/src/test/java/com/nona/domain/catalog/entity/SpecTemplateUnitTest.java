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
 * 规格模板值对象契约测试：维度结构校验（名称非空/唯一、值非空/值内唯一）、
 * 组合展开（笛卡尔积、顺序、总数量）、派生值（可读摘要、规范化摘要的
 * 定长与顺序无关性）。
 * <p>
 * 覆盖：happy（双维度组合展开与总数、单维度）、critical（空模板、单值
 * 维度、hash 与维度配置顺序无关、维度调序后同类组合匹配稳定）、
 * error（维度名空/重复、值空/值内重复、null 值列表、维度元素缺失）。
 * <p>
 * 红状态说明：本类覆盖的构造与派生行为均为契约声明（方法体抛
 * UnsupportedOperationException）——所有用例红，红因 = 实现缺失。
 */
class SpecTemplateUnitTest {

    /**
     * SHA-256 十六进制形态（64 字符定长）
     */
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    /**
     * happy：双维度模板组合数为各维度值数乘积，展开序 = 维度配置序
     * 笛卡尔积（首维度最外层，内层先耗尽）。
     */
    @Test
    @DisplayName("双维度模板展开全部组合且首维度最外层")
    void combinations_twoDimensionsCartesianOrder() {
        final SpecTemplate template = template(dim("颜色", "黑", "白"), dim("尺寸", "L", "XL"));

        assertThat(template.isEmpty()).isFalse();
        assertThat(template.combinationCount()).isEqualTo(4);
        final List<SpecCombination> combinations = template.combinations();
        assertThat(combinations).hasSize(4);
        assertThat(combinations).extracting(SpecCombination::summary)
                .containsExactly(
                        "颜色:黑,尺寸:L",
                        "颜色:黑,尺寸:XL",
                        "颜色:白,尺寸:L",
                        "颜色:白,尺寸:XL");
        assertThat(combinations).allSatisfy(combination -> {
            assertThat(combination.hash()).matches(HEX64);
            assertThat(combination.valuesOrdered()).hasSize(2);
            assertThat(combination.valueOf("颜色")).isNotBlank();
            assertThat(combination.valueOf("尺寸")).isNotBlank();
        });
    }

    /**
     * happy：单维度模板按值数生成组合（1×N）。
     */
    @Test
    @DisplayName("单维度模板按值数展开组合")
    void combinations_singleDimensionExpandsByValueCount() {
        final SpecTemplate template = template(dim("颜色", "黑", "白", "蓝"));

        assertThat(template.combinationCount()).isEqualTo(3);
        assertThat(template.combinations()).extracting(SpecCombination::summary)
                .containsExactly("颜色:黑", "颜色:白", "颜色:蓝");
    }

    /**
     * critical：空模板（null 或空维度列表）合法——组合数 0、展开为空。
     */
    @Test
    @DisplayName("空模板组合数为零且为空形态")
    void emptyTemplate_isEmptyWithZeroCombinations() {
        final SpecTemplate fromNull = new SpecTemplate(null);
        final SpecTemplate fromEmpty = new SpecTemplate(List.of());

        for (final SpecTemplate empty : List.of(fromNull, fromEmpty)) {
            assertThat(empty.isEmpty()).isTrue();
            assertThat(empty.combinationCount()).isZero();
            assertThat(empty.combinations()).isEmpty();
        }
    }

    /**
     * critical：单值维度模板（1×1×1）生成唯一组合。
     */
    @Test
    @DisplayName("全部单值维度生成唯一组合")
    void combinations_singleValuesProduceOneCombination() {
        final SpecTemplate template = template(
                dim("颜色", "黑"), dim("尺寸", "L"), dim("款式", "经典"));

        assertThat(template.combinationCount()).isEqualTo(1);
        assertThat(template.combinations()).extracting(SpecCombination::summary)
                .containsExactly("颜色:黑,尺寸:L,款式:经典");
    }

    /**
     * critical：specHash 与维度配置顺序无关（规范化摘要）——同一组合
     * 跨模板（维度调序）身份稳定，组合匹配语义由此成立。
     */
    @Test
    @DisplayName("同组合的哈希与维度配置顺序无关")
    void hash_isIndependentOfDimensionOrder() {
        final SpecTemplate ordered = template(dim("颜色", "黑", "白"), dim("尺寸", "L", "XL"));
        final SpecTemplate reversed = template(dim("尺寸", "L", "XL"), dim("颜色", "黑", "白"));

        for (final SpecCombination combination : ordered.combinations()) {
            final SpecCombination counterpart = reversed.combinations().stream()
                    .filter(candidate -> candidate.valuesOrdered().stream().allMatch(ref ->
                            combination.valuesOrdered().stream()
                                    .anyMatch(other -> other.getName().equals(ref.getName())
                                            && other.getValue().equals(ref.getValue()))))
                    .findFirst()
                    .orElseThrow();
            assertThat(combination.hash())
                    .as("组合 %s 跨模板哈希稳定", combination.summary())
                    .isEqualTo(counterpart.hash());
        }
    }

    /**
     * critical：不同组合哈希互异（摘要可区分全部组合，唯一性判定可靠）。
     */
    @Test
    @DisplayName("不同组合哈希互异")
    void hash_distinguishesDifferentCombinations() {
        final SpecTemplate template = template(dim("颜色", "黑", "白"), dim("尺寸", "L", "XL"));

        final List<String> hashes = template.combinations().stream().map(SpecCombination::hash).toList();
        assertThat(hashes).doesNotHaveDuplicates();
    }

    /**
     * error：维度值为空/空白拒绝（含整表空值列表）。
     */
    @Test
    @DisplayName("空规格值列表与空白元素拒绝")
    void item_blankValuesRejected() {
        assertThatThrownBy(() -> new SpecItem("颜色", List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("规格值不能为空");
        assertThatThrownBy(() -> new SpecItem("颜色", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("规格值不能为空");
        assertThatThrownBy(() -> new SpecItem("颜色", List.of("黑", " ")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("规格值不能为空");
    }

    /**
     * error：维度内值重复拒绝（重复值使同一规格组合重复生成）。
     */
    @Test
    @DisplayName("维度内值重复拒绝")
    void item_duplicateValuesRejected() {
        assertThatThrownBy(() -> new SpecItem("颜色", List.of("黑", "黑")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("规格值重复");
        assertThatThrownBy(() -> new SpecItem("颜色", List.of("黑", "白", "黑")))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：维度名为空/空白拒绝。
     */
    @Test
    @DisplayName("空维度名拒绝")
    void item_blankNameRejected() {
        assertThatThrownBy(() -> new SpecItem("  ", List.of("黑")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("维度名不能为空");
        assertThatThrownBy(() -> new SpecItem(null, List.of("黑")))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：同模板内维度名重复拒绝（同名维度无法区分组合归属）。
     */
    @Test
    @DisplayName("维度名重复拒绝")
    void template_duplicateDimensionNameRejected() {
        assertThatThrownBy(() -> template(dim("颜色", "黑"), dim("颜色", "白")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("维度名重复");
    }

    /**
     * error：维度元素为 null 拒绝（非法模板形态）。
     */
    @Test
    @DisplayName("模板含空维度元素拒绝")
    void template_nullDimensionRejected() {
        assertThatThrownBy(() -> new SpecTemplate(Arrays.asList(dim("颜色", "黑"), null)))
                .isInstanceOf(BusinessException.class);
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
}