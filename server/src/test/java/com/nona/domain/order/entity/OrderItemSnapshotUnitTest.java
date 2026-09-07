package com.nona.domain.order.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单项快照单元测试：TD-09 快照列 + JSON 扩展列模型——高频字段固化
 * （商品名/单价/数量/小计/主图 URL/规格摘要）、扩展属性 Map 冻结（保序
 * 不可变副本）、快照自洽恒等式（小计 == 单价 × 数量）与构造防御
 * （引用/名称/金额/数量/键校验），以及 B7.6 快照冻结语义（构造后不可变）。
 *
 * @author nona9961
 */
class OrderItemSnapshotUnitTest {

    /**
     * 测试商品 ID
     */
    private static final long PRODUCT_ID = 2001L;

    /**
     * 测试 SKU ID
     */
    private static final long SKU_ID = 3001L;

    /**
     * happy：完整快照构造，全部高频字段与扩展 JSON 列内容读取正确。
     */
    @Test
    @DisplayName("完整快照构造与字段读取")
    void snapshot_withAllFields_accessible() {
        final OrderItem item = new OrderItem(PRODUCT_ID, SKU_ID, "冬季羽绒服", 19900L, 2, 39800L,
                "https://img.example.com/down-jacket.jpg", "颜色:黑,尺码:XL",
                map("颜色", "黑", "尺码", "XL"), map("面料", "尼龙"));

        assertThat(item.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(item.getSkuId()).isEqualTo(SKU_ID);
        assertThat(item.getProductName()).isEqualTo("冬季羽绒服");
        assertThat(item.getUnitPrice()).isEqualTo(19900L);
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.getSubtotal()).isEqualTo(39800L);
        assertThat(item.getMainImageUrl()).isEqualTo("https://img.example.com/down-jacket.jpg");
        assertThat(item.getSpecSummary()).isEqualTo("颜色:黑,尺码:XL");
        assertThat(item.getSpecAttributes()).containsEntry("颜色", "黑").containsEntry("尺码", "XL");
        assertThat(item.getCustomAttributes()).containsEntry("面料", "尼龙");
    }

    /**
     * happy：规格键值对保持装配序（JSON 列保序语义——摘要拼接与详情展示依赖）。
     */
    @Test
    @DisplayName("规格扩展属性保序")
    void specAttributes_keepInsertionOrder() {
        final Map<String, String> specs = new LinkedHashMap<>();
        specs.put("颜色", "黑");
        specs.put("尺码", "XL");
        specs.put("版型", "宽松");

        final OrderItem item = new OrderItem(PRODUCT_ID, SKU_ID, "商品", 100L, 1, 100L,
                null, null, specs, null);

        assertThat(item.getSpecAttributes().keySet()).containsExactly("颜色", "尺码", "版型");
    }

    /**
     * happy：可空展示字段（主图 URL/规格摘要）允许缺失——初始化时主图缺失
     * 与无规格 SKU 的兜底形态。
     */
    @Test
    @DisplayName("主图与规格摘要可空")
    void snapshot_nullableDisplayFields_allowed() {
        final OrderItem item = new OrderItem(PRODUCT_ID, SKU_ID, "商品", 100L, 1, 100L,
                null, null, null, null);

        assertThat(item.getMainImageUrl()).isNull();
        assertThat(item.getSpecSummary()).isNull();
    }

    /**
     * happy：null 扩展集合按不可变空集合处理（无规格/无自定义属性的下单项）。
     */
    @Test
    @DisplayName("null 扩展集合归一为空不可变集合")
    void snapshot_nullExtMap_returnsEmptyImmutableMap() {
        final OrderItem item = new OrderItem(PRODUCT_ID, SKU_ID, "商品", 100L, 1, 100L,
                null, null, null, null);

        assertThat(item.getSpecAttributes()).isEmpty();
        assertThat(item.getCustomAttributes()).isEmpty();
        assertThatThrownBy(() -> item.getSpecAttributes().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * happy：扩展属性值为空时保留条目（与商品域自定义属性「值可空」语义对齐，
     * JSON 列保真）。
     */
    @Test
    @DisplayName("扩展属性空值条目保留")
    void snapshot_nullAttributeValue_preserved() {
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("洗涤说明", null);

        final OrderItem item = new OrderItem(PRODUCT_ID, SKU_ID, "商品", 100L, 1, 100L,
                null, null, null, attributes);

        assertThat(item.getCustomAttributes()).containsKey("洗涤说明");
        assertThat(item.getCustomAttributes().get("洗涤说明")).isNull();
    }

    /**
     * boundary：零单价免费赠品（单价 0、数量 1、小计 0）可构造。
     */
    @Test
    @DisplayName("零单价快照可构造")
    void snapshot_zeroUnitPrice_boundaryAllowed() {
        final OrderItem item = new OrderItem(PRODUCT_ID, SKU_ID, "赠品", 0L, 1, 0L,
                null, null, null, null);

        assertThat(item.getUnitPrice()).isZero();
        assertThat(item.getSubtotal()).isZero();
    }

    /**
     * freeze：构造后外部修改传入 map 不影响快照（不可变副本语义）。
     */
    @Test
    @DisplayName("外部修改传入集合不影响快照")
    void snapshot_mutateSourceMap_isolated() {
        final Map<String, String> specs = new LinkedHashMap<>();
        specs.put("颜色", "黑");

        final OrderItem item = new OrderItem(PRODUCT_ID, SKU_ID, "商品", 100L, 1, 100L,
                null, null, specs, null);
        specs.put("尺码", "XL");

        assertThat(item.getSpecAttributes()).containsOnlyKeys("颜色");
    }

    /**
     * freeze：读取返回的扩展集合不可变（写入拒绝）。
     */
    @Test
    @DisplayName("读取返回的扩展集合不可变")
    void snapshot_returnedMap_immutable() {
        final OrderItem item = new OrderItem(PRODUCT_ID, SKU_ID, "商品", 100L, 1, 100L,
                null, null, map("颜色", "黑"), null);

        assertThatThrownBy(() -> item.getSpecAttributes().put("尺码", "XL"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(item.getSpecAttributes()).containsOnlyKeys("颜色");
    }

    /**
     * freeze：快照无任何变更路径——字段全 final、无一字面 set 方法
     * （冻结语义的反射层确认）。
     */
    @Test
    @DisplayName("快照冻结：字段 final 且无 setter")
    void snapshot_noMutators() throws Exception {
        for (final Field field : OrderItem.class.getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("字段 {} 必须为 final（快照冻结）", field.getName()).isTrue();
        }
        for (final Method method : OrderItem.class.getDeclaredMethods()) {
            assertThat(method.getName()).as("快照不得暴露 setter {}：{}", method.getName(), method)
                    .doesNotStartWith("set");
        }
    }

    /**
     * error：商品引用缺失拒绝。
     */
    @Test
    @DisplayName("商品引用缺失拒绝")
    void snapshot_nullProductId_rejected() {
        assertThatThrownBy(() -> new OrderItem(null, SKU_ID, "商品", 100L, 1, 100L,
                null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.ORDER_SNAPSHOT_ITEM_INVALID.code()));
    }

    /**
     * error：SKU 引用缺失拒绝。
     */
    @Test
    @DisplayName("SKU 引用缺失拒绝")
    void snapshot_nullSkuId_rejected() {
        assertThatThrownBy(() -> new OrderItem(PRODUCT_ID, null, "商品", 100L, 1, 100L,
                null, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：商品名空白拒绝（快照展示与防改价语义要求名称固化）。
     */
    @Test
    @DisplayName("商品名空白拒绝")
    void snapshot_blankProductName_rejected() {
        assertThatThrownBy(() -> new OrderItem(PRODUCT_ID, SKU_ID, "  ", 100L, 1, 100L,
                null, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：负单价拒绝。
     */
    @Test
    @DisplayName("负单价拒绝")
    void snapshot_negativeUnitPrice_rejected() {
        assertThatThrownBy(() -> new OrderItem(PRODUCT_ID, SKU_ID, "商品", -1L, 1, -1L,
                null, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：零/负数量拒绝（无 0 件订单项）。
     */
    @Test
    @DisplayName("零与负数量拒绝")
    void snapshot_nonPositiveQuantity_rejected() {
        assertThatThrownBy(() -> new OrderItem(PRODUCT_ID, SKU_ID, "商品", 100L, 0, 0L,
                null, null, null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new OrderItem(PRODUCT_ID, SKU_ID, "商品", 100L, -2, -200L,
                null, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：小计与单价 × 数量不自洽拒绝（防装配错乱）。
     */
    @Test
    @DisplayName("小计与单价×数量不符拒绝")
    void snapshot_inconsistentSubtotal_rejected() {
        assertThatThrownBy(() -> new OrderItem(PRODUCT_ID, SKU_ID, "商品", 100L, 2, 300L,
                null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                        .isEqualTo(EcommerceBusinessCode.ORDER_SNAPSHOT_ITEM_INVALID.code()));
    }

    /**
     * error：小计为负拒绝——单价 × 数量 long 溢出路径：恒等式仍可成立
     * （{@code Long.MAX_VALUE × 2} 溢出为 -2），但金额非绝不变量被破坏，
     * 防负金额快照落单（与 AmountDetail 全字段非负断言同标准）。
     */
    @Test
    @DisplayName("小计为负拒绝（含溢出路径）")
    void snapshot_negativeSubtotal_rejected() {
        assertThatThrownBy(() -> new OrderItem(PRODUCT_ID, SKU_ID, "商品", Long.MAX_VALUE, 2, -2L,
                null, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：扩展属性 null 键拒绝（JSON 列键非空语义）。
     */
    @Test
    @DisplayName("扩展属性 null 键拒绝")
    void snapshot_nullAttributeKey_rejected() {
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(null, "值");

        assertThatThrownBy(() -> new OrderItem(PRODUCT_ID, SKU_ID, "商品", 100L, 1, 100L,
                null, null, null, attributes))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：扩展属性空白键拒绝。
     */
    @Test
    @DisplayName("扩展属性空白键拒绝")
    void snapshot_blankAttributeKey_rejected() {
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("  ", "值");

        assertThatThrownBy(() -> new OrderItem(PRODUCT_ID, SKU_ID, "商品", 100L, 1, 100L,
                null, null, null, attributes))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 构造键值对 Map（保序）。
     *
     * @param entries 键值交替序列
     * @return LinkedHashMap
     */
    private static Map<String, String> map(String... entries) {
        final Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put(entries[i], entries[i + 1]);
        }
        return result;
    }
}