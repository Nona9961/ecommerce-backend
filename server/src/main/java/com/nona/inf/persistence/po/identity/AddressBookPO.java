package com.nona.inf.persistence.po.identity;

import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 买家地址簿聚合根持久化对象（address_book 表，global）：聚合根主表。
 * <p>
 * 主键 id = 地址簿独立主键（Snowflake，全局唯一）；account_id 为业务关联列
 * （一个买家一本簿，唯一约束）。从表 {@link AddressPO} 以 book_id 关联本根行。
 *
 * @author nona9961
 */
@Entity
@Table(name = "address_book", uniqueConstraints = {
        @UniqueConstraint(name = "uk_address_book_account", columnNames = "account_id")
})
public class AddressBookPO extends BasePO {

    /**
     * 归属买家账号 ID（业务关联列，一个买家一本簿）
     */
    @Column(nullable = false, name = "account_id")
    private Long accountId;

    /**
     * 归属买家账号 ID。
     *
     * @return 买家账号 ID
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 设置归属买家账号 ID。
     *
     * @param accountId 买家账号 ID
     */
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }
}