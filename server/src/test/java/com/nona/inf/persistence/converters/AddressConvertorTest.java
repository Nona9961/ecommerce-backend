package com.nona.inf.persistence.converters;

import com.nona.domain.identity.entity.Address;
import com.nona.inf.persistence.po.identity.AddressPO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 地址转换器单元测试：领域实体 ↔ PO 字段映射往返一致（含 null 默认标记归一）。
 *
 * @author nona9961
 */
class AddressConvertorTest {

    /**
     * 被测转换器
     */
    private final AddressConvertor convertor = new AddressConvertor();

    /**
     * happy：实体 → PO → 实体往返字段一致。
     */
    @Test
    @DisplayName("往返转换字段一致")
    void roundTrip_keepsAllFields() {
        final Address source = new Address(90001L, 1L, "张三", "13800138000",
                "浙江省", "杭州市", "西湖区", "文一西路 100 号", true);

        final AddressPO po = convertor.toPO(source);
        assertThat(po.getId()).isEqualTo(90001L);
        assertThat(po.getBookId()).isEqualTo(1L);
        assertThat(po.getRecipient()).isEqualTo("张三");
        assertThat(po.getPhone()).isEqualTo("13800138000");
        assertThat(po.getProvince()).isEqualTo("浙江省");
        assertThat(po.getCity()).isEqualTo("杭州市");
        assertThat(po.getDistrict()).isEqualTo("西湖区");
        assertThat(po.getDetail()).isEqualTo("文一西路 100 号");
        assertThat(po.getIsDefault()).isTrue();

        final Address back = convertor.toDomain(po);
        assertThat(back.getId()).isEqualTo(source.getId());
        assertThat(back.getBookId()).isEqualTo(source.getBookId());
        assertThat(back.getRecipient()).isEqualTo(source.getRecipient());
        assertThat(back.getPhone()).isEqualTo(source.getPhone());
        assertThat(back.getProvince()).isEqualTo(source.getProvince());
        assertThat(back.getCity()).isEqualTo(source.getCity());
        assertThat(back.getDistrict()).isEqualTo(source.getDistrict());
        assertThat(back.getDetail()).isEqualTo(source.getDetail());
        assertThat(back.isDefault()).isTrue();
    }

    /**
     * critical：PO 默认标记为 null（历史异常数据）归一为 false。
     */
    @Test
    @DisplayName("空默认标记归一为 false")
    void toDomain_nullDefault_mapsToFalse() {
        final AddressPO po = new AddressPO();
        po.setId(90001L);
        po.setBookId(1L);
        po.setRecipient("张三");
        po.setPhone("13800138000");
        po.setProvince("浙江省");
        po.setCity("杭州市");
        po.setDistrict("西湖区");
        po.setDetail("文一西路 100 号");
        po.setIsDefault(null);

        assertThat(convertor.toDomain(po).isDefault()).isFalse();
    }

    /**
     * critical：未默认地址往返保持 false。
     */
    @Test
    @DisplayName("非默认地址往返保持 false")
    void roundTrip_nonDefault_keepsFalse() {
        final Address source = new Address(90002L, 1L, "李四", "13900139000",
                "广东省", "深圳市", "南山区", "科技园路 1 号", false);

        assertThat(convertor.toDomain(convertor.toPO(source)).isDefault()).isFalse();
    }
}