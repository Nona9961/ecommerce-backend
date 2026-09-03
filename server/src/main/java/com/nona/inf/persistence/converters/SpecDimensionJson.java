package com.nona.inf.persistence.converters;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 规格模板 JSON 列中间形态（{@link SpecTemplateJson} 的维度单元）：
 * 维度名 + 值列表。
 *
 * @param name   维度名称
 * @param values 维度值列表（按配置序）
 */
public record SpecDimensionJson(String name, List<String> values) {

    /**
     * 紧凑构造器：防御 null，无值以空列表呈现（重建时由 SpecItem 构造
     * 路径执行结构校验）。
     */
    public SpecDimensionJson {
        values = Objects.requireNonNullElse(values, Collections.emptyList());
    }
}