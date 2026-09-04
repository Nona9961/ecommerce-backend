package com.nona.inf.persistence.converters;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 规格模板 JSON 列中间形态（product 主表 spec_template_json 列承载）：
 * 规格模板值对象（SpecTemplate）的无参构造缺失且组合展开不可逆，
 * 持久化以独立 JSON 形态落库，读取时经构造路径重建。
 * <p>
 * 序列化格式：{@code {"dimensions":[{"name":"颜色","values":["黑","白"]}]}}；
 * 空模板（0 维度）与非空模板的差异由 dimensions 空列表与 null 区分
 * （null=尚未配置模板）。
 *
 * @param dimensions 规格维度列表（按配置序；空列表=空模板）
 * @author nona9961
 */
public record SpecTemplateJson(List<SpecDimensionJson> dimensions) {

    /**
     * 紧凑构造器：防御 null，无维度以空列表呈现。
     */
    public SpecTemplateJson {
        dimensions = Objects.requireNonNullElse(dimensions, Collections.emptyList());
    }
}