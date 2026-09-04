package com.nona.api.mall;

import java.util.List;

/**
 * 买家商品详情-规格维度（规格选择区的一维：维度名 + 可选值列表）。
 *
 * @param name   维度名（如 颜色）
 * @param values 可选值列表（如 黑/白，按配置序）
 * @author nona9961
 */
public record BuyerSpecDimensionView(String name, List<String> values) {
}
