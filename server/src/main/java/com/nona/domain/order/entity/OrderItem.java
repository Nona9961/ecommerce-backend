package com.nona.domain.order.entity;

import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单项快照（order 域值对象，不可变）：下单时刻商品内容的固化拷贝
 * （B7.6 ② 价格/名称/图片快照固化，防商家改价错乱）。
 * <p>
 * 列模型（TD-09）：高频字段为列——商品名/单价/数量/小计/主图 URL/
 * SKU 规格摘要文本；半结构化内容（规格键值对、自定义属性快照）入 JSON
 * 扩展列——Map 形态承载，构造时拷贝为不可变副本（保序）。
 * <p>
 * 快照冻结语义：创建后不可变——全部字段 final、无任何变更路径；持有的
 * Map 为不可变副本（外部修改传入的 map 不影响快照；读取返回的 map 亦
 * 不可变，直接暴露内部引用）。
 * <p>
 * 结构不变量（收敛在构造路径，非法快照无法构造）：
 * <ol>
 *     <li>商品/SKU 引用必填（订单项定位与退款/库存回补锚点）；</li>
 *     <li>商品名必填非空（列表/详情展示语义）；</li>
 *     <li>单价非负、数量正数、小计非负且 == 单价 × 数量（快照自洽恒等式——
 *         小计为独立高频列免解析计算，但必须以恒等式锁定防装配错乱；
 *         小计另设非负断言闭合 long 溢出路径）；</li>
 *     <li>规格/自定义属性键必填非空（值为快照原样保留、可空——与商品域
 *         自定义属性值可空语义对齐，JSON 列序列化保留空值条目）；null
 *         集合按不可变空集合处理。</li>
 * </ol>
 * 金额单位均为分。
 *
 * @author nona9961
 */
public class OrderItem {

    /**
     * 商品 ID（快照溯源/售后锚点，创建时定型不可变）
     */
    private final Long productId;

    /**
     * SKU ID（库存/退款维度锚点，创建时定型不可变）
     */
    private final Long skuId;

    /**
     * 商品名快照（防改价错乱）
     */
    private final String productName;

    /**
     * 快照单价（分，非负）
     */
    private final long unitPrice;

    /**
     * 数量（正数）
     */
    private final int quantity;

    /**
     * 小计（分，快照列：单价 × 数量，列表/详情免解析直读）
     */
    private final long subtotal;

    /**
     * 主图 URL 快照（可空——初始化时商品主图缺失的兜底形态）
     */
    private final String mainImageUrl;

    /**
     * SKU 规格摘要文本（如 {@code 颜色:黑,尺码:M}；无规格 SKU 可空）
     */
    private final String specSummary;

    /**
     * 规格键值对快照（JSON 扩展列；键非空，值可空；保序不可变）
     */
    private final Map<String, String> specAttributes;

    /**
     * 自定义属性快照（JSON 扩展列；键非空，值可空；保序不可变）
     */
    private final Map<String, String> customAttributes;

    /**
     * 构造订单项快照（结构与自洽校验收敛在本构造路径）。
     *
     * @param productId        商品 ID（必填）
     * @param skuId            SKU ID（必填）
     * @param productName      商品名快照（必填非空）
     * @param unitPrice        快照单价（分，非负）
     * @param quantity         数量（正数）
     * @param subtotal         小计（分，必须等于单价 × 数量）
     * @param mainImageUrl     主图 URL 快照（可空）
     * @param specSummary      SKU 规格摘要文本（可空）
     * @param specAttributes   规格键值对（可空；拷贝为不可变副本）
     * @param customAttributes 自定义属性快照（可空；拷贝为不可变副本）
     */
    public OrderItem(Long productId, Long skuId, String productName, long unitPrice,
                     int quantity, long subtotal, String mainImageUrl, String specSummary,
                     Map<String, String> specAttributes, Map<String, String> customAttributes) {
        BusinessAssert.assertNonNull(EcommerceBusinessCode.ORDER_SNAPSHOT_ITEM_INVALID.code(),
                productId, "订单项快照商品引用不能为空");
        BusinessAssert.assertNonNull(EcommerceBusinessCode.ORDER_SNAPSHOT_ITEM_INVALID.code(),
                skuId, "订单项快照 SKU 引用不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_ITEM_INVALID.code(),
                productName != null && !productName.isBlank(), "订单项快照商品名不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_ITEM_INVALID.code(),
                unitPrice >= 0, "订单项快照单价不能为负：{}", unitPrice);
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_ITEM_INVALID.code(),
                quantity >= 1, "订单项快照数量必须为正数：{}", quantity);
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_ITEM_INVALID.code(),
                subtotal == unitPrice * quantity, "订单项快照小计与单价×数量不符：{} != {}×{}",
                subtotal, unitPrice, quantity);
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_ITEM_INVALID.code(),
                subtotal >= 0, "订单项快照小计不能为负：{}", subtotal);
        this.productId = productId;
        this.skuId = skuId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.subtotal = subtotal;
        this.mainImageUrl = mainImageUrl;
        this.specSummary = specSummary;
        this.specAttributes = freezeAttributes(specAttributes);
        this.customAttributes = freezeAttributes(customAttributes);
    }

    /**
     * 商品 ID。
     *
     * @return 商品 ID
     */
    public Long getProductId() {
        return productId;
    }

    /**
     * SKU ID。
     *
     * @return SKU ID
     */
    public Long getSkuId() {
        return skuId;
    }

    /**
     * 商品名快照。
     *
     * @return 商品名
     */
    public String getProductName() {
        return productName;
    }

    /**
     * 快照单价（分）。
     *
     * @return 单价
     */
    public long getUnitPrice() {
        return unitPrice;
    }

    /**
     * 数量。
     *
     * @return 数量
     */
    public int getQuantity() {
        return quantity;
    }

    /**
     * 小计（分）。
     *
     * @return 小计
     */
    public long getSubtotal() {
        return subtotal;
    }

    /**
     * 主图 URL 快照。
     *
     * @return 主图 URL；初始化时商品主图缺失为 null
     */
    public String getMainImageUrl() {
        return mainImageUrl;
    }

    /**
     * SKU 规格摘要文本。
     *
     * @return 摘要文本；无规格 SKU 为 null
     */
    public String getSpecSummary() {
        return specSummary;
    }

    /**
     * 规格键值对快照（不可变，保序）。
     *
     * @return 规格键值对；无规格为不可变空集合
     */
    public Map<String, String> getSpecAttributes() {
        return specAttributes;
    }

    /**
     * 自定义属性快照（不可变，保序）。
     *
     * @return 自定义属性；无属性为不可变空集合
     */
    public Map<String, String> getCustomAttributes() {
        return customAttributes;
    }

    /**
     * 冻结扩展属性集合为不可变副本（保序）：null 按不可变空集合处理；
     * 键必填非空（值为快照原样保留、可空——与商品域自定义属性值可空
     * 语义对齐）。
     *
     * @param attributes 原始集合（可空）
     * @return 不可变副本（LinkedHashMap 保序）
     */
    private static Map<String, String> freezeAttributes(Map<String, String> attributes) {
        if (attributes == null) {
            return Map.of();
        }
        final Map<String, String> copy = new LinkedHashMap<>(attributes);
        for (final String key : copy.keySet()) {
            BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_ITEM_INVALID.code(),
                    key != null && !key.isBlank(), "订单项快照扩展属性键不能为空");
        }
        return Collections.unmodifiableMap(copy);
    }
}