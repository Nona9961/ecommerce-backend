package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 规格维度（Product 聚合规格模板的组成单元，值对象，不可变）：
 * 一个规格维度 = 维度名称 + 值列表（如「颜色」→ 黑/白）。
 * <p>
 * 结构不变量（收敛在本对象构造路径，非法维度无法构造）：
 * <ol>
 *     <li>维度名必填非空（无名维度无业务意义）；</li>
 *     <li>值列表非空且每个值必填非空（空值维度生成不出可售组合）；</li>
 *     <li>维度内值唯一（重复值会使同一规格组合重复生成，无业务意义）。</li>
 * </ol>
 * 跨维度约束（同一商品内维度名唯一）收敛在 {@link SpecTemplate}。
 * 本对象不承载 ID——规格模板整体替换语义，维度无独立生命周期，
 * 模板变更整体重建，维度自身不可寻址。
 *
 * @author nona9961
 */
public class SpecItem {

    /**
     * 维度名称
     */
    private final String name;

    /**
     * 维度值列表（保持配置序，不可变）
     */
    private final List<String> values;

    /**
     * 构造规格维度（结构校验：名称必填、值列表非空且每个值非空、值不重复）。
     *
     * @param name   维度名称（必填非空）
     * @param values 维度值列表（必填非空，元素非空且不重复）
     */
    public SpecItem(String name, List<String> values) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_DIMENSION_NAME_BLANK.code(), "维度名不能为空");
        }
        if (values == null || values.isEmpty()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_VALUE_BLANK.code(), "规格值不能为空");
        }
        final List<String> copy = new ArrayList<>(values.size());
        for (final String value : values) {
            if (value == null || value.isBlank()) {
                throw new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_VALUE_BLANK.code(), "规格值不能为空");
            }
            if (copy.contains(value)) {
                throw new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_VALUE_DUPLICATE.code(), "规格值重复");
            }
            copy.add(value);
        }
        this.name = name;
        this.values = List.copyOf(copy);
    }

    /**
     * 维度名称。
     *
     * @return 维度名称
     */
    public String getName() {
        return name;
    }

    /**
     * 维度值列表快照（不可变副本，保持配置序）。
     *
     * @return 维度值列表
     */
    public List<String> valuesOrdered() {
        return values;
    }
}