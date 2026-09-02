package com.nona.domain.identity.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 买家收货地址簿聚合根：买家维度的地址集合 + 默认标记（address_book 主表 + address 从表）。
 * <p>
 * 聚合根拥有独立主键 bookId（Snowflake，address_book 表主键）；accountId 为业务关联列
 * （一个买家一本簿，account_id 唯一）。从表地址行以 book_id（rootId）关联本聚合。
 * 关键不变量：同买家至多一个默认地址；设置新默认自动让位旧默认（覆盖语义）。
 * 不变量收敛在本聚合（add/setDefault/remove 都经过统一迁移逻辑），外部只能通过
 * 聚合方法变更地址簿；默认标记的写入入口（Address.markDefault）仅对本聚合可见。
 *
 * @author nona9961
 */
public class AddressBook {

    /**
     * 地址簿主键（Snowflake，聚合根标识）
     */
    private final Long bookId;

    /**
     * 归属买家账号 ID（业务关联列，address_book.account_id 唯一）
     */
    private final Long accountId;

    /**
     * 地址集合（保持加载/追加顺序，第一条即删除默认后的自动提升候选）
     */
    private final List<Address> addresses = new ArrayList<>();

    /**
     * 构造地址簿（仅 Factory 与仓储加载重建调用）。
     *
     * @param bookId    地址簿主键
     * @param accountId 归属买家账号 ID
     */
    public AddressBook(Long bookId, Long accountId) {
        this.bookId = bookId;
        this.accountId = accountId;
    }

    /**
     * 地址簿主键。
     *
     * @return 主键
     */
    public Long getId() {
        return bookId;
    }

    /**
     * 归属买家账号 ID。
     *
     * @return 买家账号 ID
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 地址条数。
     *
     * @return 条数
     */
    public int size() {
        return addresses.size();
    }

    /**
     * 地址集合快照（不可变视图，读路径使用）。
     *
     * @return 地址快照
     */
    public List<Address> snapshot() {
        return List.copyOf(addresses);
    }

    /**
     * 按 ID 取地址。
     *
     * @param addressId 地址 ID
     * @return 地址；不存在返回空
     */
    public Optional<Address> getById(Long addressId) {
        return addresses.stream().filter(address -> address.getId().equals(addressId)).findFirst();
    }

    /**
     * 当前默认地址。
     *
     * @return 默认地址；无默认返回空
     */
    public Optional<Address> getDefault() {
        return addresses.stream().filter(Address::isDefault).findFirst();
    }

    /**
     * 新增地址：同 ID 重复拒绝；带默认标记时自动让位既有默认（新设覆盖旧默认）。
     *
     * @param address 新地址
     */
    public void add(Address address) {
        BusinessAssert.assertNonNull(address, "地址不能为空");
        BusinessAssert.assertTrue(getById(address.getId()).isEmpty(), "地址已存在：{}", address.getId());
        if (address.isDefault()) {
            clearDefault();
        }
        addresses.add(address);
        assertInvariants();
    }

    /**
     * 更新地址（编辑路径）：地址必须存在；默认标记不被编辑触碰（以簿内现值保持）。
     *
     * @param updated 更新后的地址（ID 必须已存在）
     */
    public void update(Address updated) {
        BusinessAssert.assertNonNull(updated, "地址不能为空");
        final Address existing = existing(updated.getId());
        final int index = addresses.indexOf(existing);
        addresses.set(index, updated);
        assertInvariants();
    }

    /**
     * 设置默认地址：目标必须存在；已是默认则幂等；否则旧默认让位、目标置默认。
     *
     * @param addressId 目标地址 ID
     */
    public void setDefault(Long addressId) {
        final Address target = existing(addressId);
        if (target.isDefault()) {
            return;
        }
        clearDefault();
        target.markDefault(true);
        assertInvariants();
    }

    /**
     * 删除地址：目标必须存在；删除默认地址后若簿内仍有地址，自动提升第一条为默认
     * （地址簿不存在「无默认但有地址」的悬挂状态）。
     *
     * @param addressId 地址 ID
     */
    public void remove(Long addressId) {
        final Address target = existing(addressId);
        final boolean removedDefault = target.isDefault();
        addresses.remove(target);
        if (removedDefault && !addresses.isEmpty()) {
            addresses.get(0).markDefault(true);
        }
        assertInvariants();
    }

    /**
     * 清空默认标记（迁移前统一操作）。
     */
    private void clearDefault() {
        addresses.forEach(address -> address.markDefault(false));
    }

    /**
     * 按 ID 取地址并断言存在（编辑/删除/设置默认的目标必须属于本簿；
     * 不属于本买家即呈现为不存在，不泄露归属信息）。
     *
     * @param addressId 地址 ID
     * @return 地址
     */
    private Address existing(Long addressId) {
        return getById(addressId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.IDENTITY_ADDRESS_NOT_FOUND.code(), "地址不存在", 404));
    }

    /**
     * 不变量守卫：同簿至多一个默认地址（防御性，逻辑正确时恒成立）。
     */
    private void assertInvariants() {
        final long defaultCount = addresses.stream().filter(Address::isDefault).count();
        BusinessAssert.assertTrue(defaultCount <= 1, "默认地址唯一性被破坏：{}", defaultCount);
    }
}