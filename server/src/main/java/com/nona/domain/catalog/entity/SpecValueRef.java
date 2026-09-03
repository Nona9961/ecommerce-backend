package com.nona.domain.catalog.entity;

/**
 * 规格组合取值对（值对象，不可变）：一个维度名与其取值的绑定，
 * {@link SpecCombination} 的组成单元（如 颜色 → 黑）。
 * <p>
 * 数据完整性防御（构造路径）：维度名与取值必填非空——空名/空值
 * 的取值对无业务意义，组合派生依赖取值对完整性。
 *
 * @author nona9961
 */
public class SpecValueRef {

    /**
     * 维度名
     */
    private final String name;

    /**
     * 取值
     */
    private final String value;

    /**
     * 构造取值对（防御：名称与取值必填非空）。
     *
     * @param name  维度名
     * @param value 取值
     */
    public SpecValueRef(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("维度名不能为空");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("规格值不能为空");
        }
        this.name = name;
        this.value = value;
    }

    /**
     * 维度名。
     *
     * @return 维度名
     */
    public String getName() {
        return name;
    }

    /**
     * 取值。
     *
     * @return 取值
     */
    public String getValue() {
        return value;
    }
}