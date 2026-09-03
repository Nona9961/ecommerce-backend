package com.nona.domain.catalog.entity;

/**
 * 商品编辑内容分流判定结果（字段级审核白名单的路由结论）。
 * <p>
 * 编辑保存时由变更追踪 diff 投影为「变更路径集合 + 增删集合名集合」，
 * 经 {@link Product#classifyEditSensitivity} 判定路由方向：
 * <ul>
 *     <li>命中交易敏感字段集（标题/平台类目/品牌/SKU 价格/SKU 规格构成）
 *         → {@link #SENSITIVE}：商品转待审核，编辑内容入待审草稿位（买家
 *         继续可见旧版生效内容）；</li>
 *     <li>仅命中展示类字段（描述/详情图/自定义属性）→ {@link #DISPLAY_ONLY}：
 *         直接生效并生成编辑版本行，无需重审；</li>
 *     <li>无任何变更 → {@link #NONE}（空保存，不落库不插版本行）。</li>
 * </ul>
 *
 * @author nona9961
 */
public enum EditSensitivity {

    /**
     * 无变更（变更集为空）。
     */
    NONE,

    /**
     * 仅展示字段变更：直接生效（走既有保存留痕路径）。
     */
    DISPLAY_ONLY,

    /**
     * 命中交易敏感字段：转待审核（编辑内容入待审草稿位，生效内容不变）。
     */
    SENSITIVE
}