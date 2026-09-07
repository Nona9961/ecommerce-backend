package com.nona.domain.order.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 收货地址快照单元测试：TD-09 地址快照列模型——六段必填校验收敛
 * （收件人/电话/省市区/详细地址）、原样保留语义，以及 B7.6 快照冻结
 * 语义（构造后不可变：字段全 final、无任何变更路径）。
 *
 * @author nona9961
 */
class AddressSnapshotUnitTest {

    /**
     * 六段地址参数索引（与构造器入参顺序严格一致）
     */
    private static final int RECIPIENT = 0;
    private static final int PHONE = 1;
    private static final int PROVINCE = 2;
    private static final int CITY = 3;
    private static final int DISTRICT = 4;
    private static final int DETAIL = 5;

    /**
     * happy：完整六段构造，全部字段读取正确。
     */
    @Test
    @DisplayName("完整地址快照构造与字段读取")
    void snapshot_withAllSegments_accessible() {
        final AddressSnapshot snapshot = build(
                "张三", "13800138000", "浙江省", "杭州市", "西湖区", "文三路 1 号");

        assertThat(snapshot.getRecipient()).isEqualTo("张三");
        assertThat(snapshot.getPhone()).isEqualTo("13800138000");
        assertThat(snapshot.getProvince()).isEqualTo("浙江省");
        assertThat(snapshot.getCity()).isEqualTo("杭州市");
        assertThat(snapshot.getDistrict()).isEqualTo("西湖区");
        assertThat(snapshot.getDetail()).isEqualTo("文三路 1 号");
    }

    /**
     * boundary：段内含非空白字符即可构造且原样保留（快照不裁剪——
     * 地址簿侧入参已规范化，快照只做非空校验不改变内容）。
     */
    @Test
    @DisplayName("含内嵌空白地址原样保留")
    void snapshot_segmentsWithInnerSpaces_preserved() {
        final AddressSnapshot snapshot = build(
                " 张 三 ", "138 0013 8000", " 浙江省 ", "杭州市", "西湖区", "文三路 1 号 ");

        assertThat(snapshot.getRecipient()).isEqualTo(" 张 三 ");
        assertThat(snapshot.getDetail()).isEqualTo("文三路 1 号 ");
    }

    /**
     * error：六段任一为 null 均拒绝（订单地址必须完整可交付）。
     */
    @Test
    @DisplayName("六段任一缺失拒绝")
    void snapshot_nullSegment_rejected() {
        for (int segment = 0; segment <= DETAIL; segment++) {
            final int index = segment;
            assertThatThrownBy(() -> buildWithNullAt(index))
                    .as("第 {} 段为 null 应拒绝", index + 1)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SNAPSHOT_ADDRESS_INVALID.code()));
        }
    }

    /**
     * error：六段任一为全空白均拒绝（空白与缺失同语义，防空壳地址落单）。
     */
    @Test
    @DisplayName("六段任一空白拒绝")
    void snapshot_blankSegment_rejected() {
        for (int segment = 0; segment <= DETAIL; segment++) {
            final int index = segment;
            assertThatThrownBy(() -> buildWithBlankAt(index))
                    .as("第 {} 段为空白应拒绝", index + 1)
                    .isInstanceOf(BusinessException.class);
        }
    }

    /**
     * freeze：快照无任何变更路径——字段全 final、无一字面 set 方法
     * （冻结语义的反射层确认，与 OrderItem 快照同标准）。
     */
    @Test
    @DisplayName("快照冻结：字段 final 且无 setter")
    void snapshot_noMutators() throws Exception {
        for (final Field field : AddressSnapshot.class.getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("字段 {} 必须为 final（快照冻结）", field.getName()).isTrue();
        }
        for (final Method method : AddressSnapshot.class.getDeclaredMethods()) {
            assertThat(method.getName()).as("快照不得暴露 setter {}：{}", method.getName(), method)
                    .doesNotStartWith("set");
        }
    }

    /**
     * 构造六段完整地址快照。
     *
     * @param recipient 收件人
     * @param phone     电话
     * @param province  省份
     * @param city      城市
     * @param district  区县
     * @param detail    详细地址
     * @return 地址快照
     */
    private static AddressSnapshot build(String recipient, String phone, String province,
                                         String city, String district, String detail) {
        return new AddressSnapshot(recipient, phone, province, city, district, detail);
    }

    /**
     * 构造指定段为 null 的地址快照（按构造器入参顺序定位）。
     *
     * @param segment 段索引（0-5）
     * @return 以 null 替换指定段的快照构造结果
     */
    private static AddressSnapshot buildWithNullAt(int segment) {
        return buildWith(replacedBy -> null, segment);
    }

    /**
     * 构造指定段为全空白的地址快照（按构造器入参顺序定位）。
     *
     * @param segment 段索引（0-5）
     * @return 以空白替换指定段的快照构造结果
     */
    private static AddressSnapshot buildWithBlankAt(int segment) {
        return buildWith(replacedBy -> "   ", segment);
    }

    /**
     * 以替换函数构造地址快照：非目标段取默认值，目标段按替换函数取值
     * （段定位与构造器入参顺序严格一致）。
     *
     * @param replacement 目标段取值函数（入参为段默认值，返回替换值）
     * @param segment     段索引（0-5）
     * @return 快照构造结果
     */
    private static AddressSnapshot buildWith(Function<String, String> replacement, int segment) {
        final String[] segments = {"张三", "13800138000", "浙江省", "杭州市", "西湖区", "文三路 1 号"};
        segments[segment] = replacement.apply(segments[segment]);
        return new AddressSnapshot(segments[RECIPIENT], segments[PHONE], segments[PROVINCE],
                segments[CITY], segments[DISTRICT], segments[DETAIL]);
    }
}