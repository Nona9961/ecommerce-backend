package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 规格模板（Product 聚合内值对象，不可变，整体替换语义）：商品可售
 * 单元（SKU）集的生成依据——规格维度列表，SKU 组合 = 各维度值集的
 * 笛卡尔积。
 * <p>
 * 结构不变量（收敛在本对象构造路径）：
 * <ol>
 *     <li>维度名必填非空且同模板内唯一（同名维度无法区分组合归属）；</li>
 *     <li>维度值约束由 {@link SpecItem} 承载（非空、值内唯一）；</li>
 *     <li>空模板（无维度）为合法形态——语义 = 清空本商品 SKU 集
 *         （模板整体替换语义下，置空即不再生成任何可售组合）。</li>
 * </ol>
 * 派生语义：
 * <ul>
 *     <li>组合展开按维度配置序笛卡尔积（首维度最外层），展开序稳定；</li>
 *     <li>每个组合的 {@code specHash} 与维度配置顺序无关（规范化
 *         摘要）——模板调整维度顺序不改变既有组合的身份，组合匹配
 *         保留语义由此成立；</li>
 *     <li>{@code specSummary} 按配置序拼接（{@code 维度:值} 逗号分隔），
 *         供买家详情与订单快照展示。</li>
 * </ul>
 * 组合数上限（防组合爆炸）不属模板结构约束，由 Product 聚合配置路径
 * 统一守卫（{@link Product#MAX_SKU_COMBINATIONS}）。
 *
 * @author nona9961
 */
public class SpecTemplate {

    /**
     * 规格维度列表（保持配置序，不可变）
     */
    private final List<SpecItem> dimensions;

    /**
     * 构造规格模板（结构校验：维度列表可空=空模板，维度名唯一）。
     *
     * @param dimensions 规格维度列表（按配置序；null 或空 = 空模板）
     */
    public SpecTemplate(List<SpecItem> dimensions) {
        final List<SpecItem> copy = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        if (dimensions != null) {
            for (final SpecItem dimension : dimensions) {
                if (dimension == null) {
                    throw new BusinessException(
                            EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_INVALID.code(), "维度不能为空");
                }
                if (!names.add(dimension.getName())) {
                    throw new BusinessException(
                            EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_DIMENSION_DUPLICATE.code(), "维度名重复");
                }
            }
            copy.addAll(dimensions);
        }
        this.dimensions = List.copyOf(copy);
    }

    /**
     * 规格维度列表快照（不可变副本，保持配置序）。
     *
     * @return 维度列表；空模板返回空列表
     */
    public List<SpecItem> dimensionsOrdered() {
        return dimensions;
    }

    /**
     * 是否空模板（无任何规格维度）。
     *
     * @return 空模板返回 true
     */
    public boolean isEmpty() {
        return dimensions.isEmpty();
    }

    /**
     * 组合总数（各维度值数乘积；空模板为 0）。
     *
     * @return 组合总数
     */
    public int combinationCount() {
        if (dimensions.isEmpty()) {
            return 0;
        }
        long count = 1;
        for (final SpecItem dimension : dimensions) {
            count *= dimension.valuesOrdered().size();
            if (count > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) count;
    }

    /**
     * 展开全部规格组合（维度配置序笛卡尔积，首维度最外层）。
     *
     * @return 组合列表；空模板返回空列表
     */
    public List<SpecCombination> combinations() {
        if (dimensions.isEmpty()) {
            return List.of();
        }
        final List<SpecCombination> result = new ArrayList<>();
        expand(0, new ArrayList<>(), new ArrayList<>(), result);
        return result;
    }

    /**
     * 笛卡尔积递归展开（配置序：首维度最外层，末维度先耗尽）。
     *
     * @param index  当前维度下标
     * @param names  已选维度名（递归栈）
     * @param values 已选维度值（递归栈）
     * @param result 组合累积结果
     */
    private void expand(int index, List<String> names, List<String> values,
                        List<SpecCombination> result) {
        if (index == dimensions.size()) {
            result.add(new SpecCombination(List.copyOf(names), List.copyOf(values)));
            return;
        }
        final SpecItem dimension = dimensions.get(index);
        for (final String value : dimension.valuesOrdered()) {
            names.add(dimension.getName());
            values.add(value);
            expand(index + 1, names, values, result);
            names.remove(names.size() - 1);
            values.remove(values.size() - 1);
        }
    }
}