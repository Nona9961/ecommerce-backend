package com.nona.domain.identity.factory;

import com.nona.domain.identity.entity.Address;
import com.nona.domain.identity.entity.AddressBook;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 地址簿工厂单元测试：簿与地址的创建路径（ID 生成 + 字段形态校验）。
 *
 * @author nona9961
 */
class AddressBookFactoryUnitTest {

    /**
     * 被测工厂
     */
    private final AddressBookFactory factory = new AddressBookFactory();

    /**
     * happy：创建空地址簿，归属买家锚定。
     */
    @Test
    @DisplayName("创建空地址簿")
    void createBook_returnsEmptyBook() {
        final AddressBook book = factory.createBook(10001L);

        assertThat(book.getAccountId()).isEqualTo(10001L);
        assertThat(book.size()).isZero();
    }

    /**
     * error：空买家账号拒绝创建。
     */
    @Test
    @DisplayName("空买家账号拒绝创建")
    void createBook_nullAccount_rejects() {
        assertThatThrownBy(() -> factory.createBook(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("买家账号");
    }

    /**
     * happy：创建地址，ID 分配且字段全部透传（默认标记随入参）。
     */
    @Test
    @DisplayName("创建地址字段透传")
    void createAddress_carriesAllFields() {
        final AddressBook book = factory.createBook(10001L);
        final Address address = factory.createAddress(book,
                "张三", "13800138000", "浙江省", "杭州市", "西湖区", "文一西路 100 号", true);

        assertThat(address.getId()).isNotNull();
        assertThat(address.getBookId()).isEqualTo(book.getId());
        assertThat(address.getRecipient()).isEqualTo("张三");
        assertThat(address.getPhone()).isEqualTo("13800138000");
        assertThat(address.getProvince()).isEqualTo("浙江省");
        assertThat(address.getCity()).isEqualTo("杭州市");
        assertThat(address.getDistrict()).isEqualTo("西湖区");
        assertThat(address.getDetail()).isEqualTo("文一西路 100 号");
        assertThat(address.isDefault()).isTrue();
    }

    /**
     * critical：非默认地址创建，默认标记为 false。
     */
    @Test
    @DisplayName("创建非默认地址")
    void createAddress_withoutDefault_markedFalse() {
        final AddressBook book = factory.createBook(10001L);
        final Address address = factory.createAddress(book,
                "李四", "13900139000", "广东省", "深圳市", "南山区", "科技园路 1 号", false);

        assertThat(address.isDefault()).isFalse();
    }

    /**
     * error：任一个必填字段为空都拒绝创建。
     */
    @Test
    @DisplayName("必填字段为空拒绝创建")
    void createAddress_blankField_rejects() {
        final AddressBook book = factory.createBook(10001L);
        assertThatThrownBy(() -> factory.createAddress(book,
                "", "13900139000", "广东省", "深圳市", "南山区", "科技园路 1 号", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("收件人");
        assertThatThrownBy(() -> factory.createAddress(book,
                "李四", " ", "广东省", "深圳市", "南山区", "科技园路 1 号", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("联系电话");
        assertThatThrownBy(() -> factory.createAddress(book,
                "李四", "13900139000", "", "深圳市", "南山区", "科技园路 1 号", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("省份");
        assertThatThrownBy(() -> factory.createAddress(book,
                "李四", "13900139000", "广东省", null, "南山区", "科技园路 1 号", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("城市");
        assertThatThrownBy(() -> factory.createAddress(book,
                "李四", "13900139000", "广东省", "深圳市", " ", "科技园路 1 号", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("区县");
        assertThatThrownBy(() -> factory.createAddress(book,
                "李四", "13900139000", "广东省", "深圳市", "南山区", "", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("详细地址");
    }

    /**
     * error：空地址簿拒绝创建地址。
     */
    @Test
    @DisplayName("空地址簿拒绝创建地址")
    void createAddress_nullBook_rejects() {
        assertThatThrownBy(() -> factory.createAddress(null,
                "李四", "13900139000", "广东省", "深圳市", "南山区", "科技园路 1 号", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("地址簿");
    }
}