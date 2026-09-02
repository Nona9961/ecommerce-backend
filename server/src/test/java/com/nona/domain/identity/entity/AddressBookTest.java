package com.nona.domain.identity.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 地址簿聚合单元测试：默认地址唯一不变量（新增覆盖 / 设置默认迁移 / 删除自动提升）
 * 与各操作路径的成功与失败场景。
 *
 * @author nona9961
 */
class AddressBookTest {

    /**
     * 测试簿主键（固定值，聚合逻辑不依赖具体主键）
     */
    private static final long BOOK_ID = 1L;

    /**
     * 测试簿归属账号（固定值）
     */
    private static final long ACCOUNT_ID = 10001L;

    /**
     * 构造测试地址簿（bookId 独立主键 + accountId 业务关联）。
     *
     * @return 地址簿
     */
    private static AddressBook book() {
        return new AddressBook(BOOK_ID, ACCOUNT_ID);
    }

    /**
     * happy：连续新增多个地址，簿内条数递增。
     */
    @Test
    @DisplayName("新增地址成功")
    void add_appendsAddress() {
        final AddressBook book = book();
        book.add(address(1L, "张三", false));
        book.add(address(2L, "李四", false));

        assertThat(book.size()).isEqualTo(2);
        assertThat(book.snapshot()).extracting(Address::getRecipient)
                .containsExactly("张三", "李四");
        assertThat(book.getDefault()).isEmpty();
    }

    /**
     * critical：带默认标记的新地址覆盖既有默认（新增路径的让位语义）。
     */
    @Test
    @DisplayName("新增带默认的新地址让位旧默认")
    void add_withDefault_clearsPreviousDefault() {
        final AddressBook book = book();
        book.add(address(1L, "张三", true));
        book.add(address(2L, "李四", true));

        assertThat(book.getDefault()).isPresent();
        assertThat(book.getDefault().orElseThrow().getId()).isEqualTo(2L);
        assertThat(book.snapshot().stream().filter(Address::isDefault)).hasSize(1);
    }

    /**
     * error：同 ID 地址重复新增拒绝。
     */
    @Test
    @DisplayName("重复 ID 新增拒绝")
    void add_duplicateId_rejects() {
        final AddressBook book = book();
        book.add(address(1L, "张三", false));

        assertThatThrownBy(() -> book.add(address(1L, "李四", false)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：空地址新增拒绝。
     */
    @Test
    @DisplayName("空地址新增拒绝")
    void add_null_rejects() {
        final AddressBook book = book();

        assertThatThrownBy(() -> book.add(null))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * happy：设置默认迁移默认标记（旧默认让位、目标置默认）。
     */
    @Test
    @DisplayName("设置默认让位旧默认")
    void setDefault_migratesMarker() {
        final AddressBook book = book();
        book.add(address(1L, "张三", true));
        book.add(address(2L, "李四", false));

        book.setDefault(2L);

        assertThat(book.getDefault().orElseThrow().getId()).isEqualTo(2L);
        assertThat(book.snapshot().stream().filter(Address::isDefault)).hasSize(1);
    }

    /**
     * critical：无默认簿内设置第一个默认。
     */
    @Test
    @DisplayName("无默认簿内设置默认")
    void setDefault_withoutPreviousDefault() {
        final AddressBook book = book();
        book.add(address(1L, "张三", false));
        book.add(address(2L, "李四", false));

        book.setDefault(1L);

        assertThat(book.getDefault().orElseThrow().getId()).isEqualTo(1L);
        assertThat(book.snapshot().stream().filter(Address::isDefault)).hasSize(1);
    }

    /**
     * critical：已是默认的地址重复设置默认，幂等无变化。
     */
    @Test
    @DisplayName("重复设置默认幂等")
    void setDefault_idempotentWhenAlreadyDefault() {
        final AddressBook book = book();
        book.add(address(1L, "张三", true));

        book.setDefault(1L);

        assertThat(book.getDefault().orElseThrow().getId()).isEqualTo(1L);
        assertThat(book.snapshot().stream().filter(Address::isDefault)).hasSize(1);
    }

    /**
     * error：设置不存在的地址为默认拒绝。
     */
    @Test
    @DisplayName("设置不存在的地址为默认拒绝")
    void setDefault_missingId_rejects() {
        final AddressBook book = book();
        book.add(address(1L, "张三", false));

        assertThatThrownBy(() -> book.setDefault(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("地址不存在");
    }

    /**
     * happy：编辑更新地址字段，默认标记保持簿内现值。
     */
    @Test
    @DisplayName("编辑更新字段且默认标记保持")
    void update_replacesFields_keepsDefault() {
        final AddressBook book = book();
        book.add(address(1L, "张三", true));

        final Address updated = book.getById(1L).orElseThrow();
        updated.updateDetails("张三丰", "13900139000", "广东省", "深圳市", "南山区", "科技园路 2 号");
        book.update(updated);

        final Address after = book.getById(1L).orElseThrow();
        assertThat(after.getRecipient()).isEqualTo("张三丰");
        assertThat(after.getDetail()).isEqualTo("科技园路 2 号");
        assertThat(after.isDefault()).isTrue();
        assertThat(book.snapshot().stream().filter(Address::isDefault)).hasSize(1);
    }

    /**
     * error：编辑不存在的地址拒绝。
     */
    @Test
    @DisplayName("编辑不存在的地址拒绝")
    void update_missingId_rejects() {
        final AddressBook book = book();
        book.add(address(1L, "张三", false));

        final Address stranger = address(999L, "路人", false);

        assertThatThrownBy(() -> book.update(stranger))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("地址不存在");
    }

    /**
     * happy：删除地址后簿内移除。
     */
    @Test
    @DisplayName("删除地址成功")
    void remove_removesAddress() {
        final AddressBook book = book();
        book.add(address(1L, "张三", false));
        book.add(address(2L, "李四", false));

        book.remove(1L);

        assertThat(book.size()).isEqualTo(1);
        assertThat(book.snapshot().get(0).getId()).isEqualTo(2L);
    }

    /**
     * critical：删除默认地址后自动提升第一条为默认（簿内不悬挂无默认状态）。
     */
    @Test
    @DisplayName("删除默认地址自动提升第一条")
    void removeDefault_promotesFirst() {
        final AddressBook book = book();
        book.add(address(1L, "张三", false));
        book.add(address(2L, "李四", true));
        book.add(address(3L, "王五", false));

        book.remove(2L);

        assertThat(book.snapshot().stream().filter(Address::isDefault)).hasSize(1);
        assertThat(book.getDefault().orElseThrow().getId()).isEqualTo(1L);
    }

    /**
     * critical：删除最后一条地址后簿为空且无默认。
     */
    @Test
    @DisplayName("删除最后一条地址后无默认")
    void removeDefault_lastOne_leavesNoDefault() {
        final AddressBook book = book();
        book.add(address(1L, "张三", true));

        book.remove(1L);

        assertThat(book.size()).isZero();
        assertThat(book.getDefault()).isEmpty();
    }

    /**
     * error：删除不存在的地址拒绝。
     */
    @Test
    @DisplayName("删除不存在的地址拒绝")
    void remove_missingId_rejects() {
        final AddressBook book = book();
        book.add(address(1L, "张三", false));

        assertThatThrownBy(() -> book.remove(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("地址不存在");
    }

    /**
     * 构造簿内地址实体（直接构造，ID 显式可控，bookId 与簿一致）。
     *
     * @param id        地址 ID
     * @param recipient 收件人
     * @param isDefault 默认标记
     * @return 地址
     */
    private static Address address(Long id, String recipient, boolean isDefault) {
        return new Address(id, BOOK_ID, recipient, "13800138000",
                "浙江省", "杭州市", "西湖区", "文一西路 100 号", isDefault);
    }
}