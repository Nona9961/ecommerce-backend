package com.nona.domain.identity.factory;

import com.nona.domain.identity.entity.Address;
import com.nona.domain.identity.entity.AddressBook;
import com.nona.util.BusinessAssert;
import com.nona.util.IDUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 地址簿聚合根工厂：地址簿与地址的创建入口（ID 生成 + 字段形态校验）。
 * <p>
 * 聚合创建统一经工厂 + {@link IDUtils#generateID()}；地址簿拥有独立主键 bookId，
 * accountId 仅作业务关联列；地址以 bookId（rootId）关联所属簿，重复校验与默认
 * 让位由 {@link AddressBook} 聚合方法保证。
 *
 * @author nona9961
 */
@Component
public class AddressBookFactory {

    /**
     * 创建空地址簿（买家维度；买家信息保密性：账号存在性由登录链路保证）。
     * 主键 bookId 独立生成（Snowflake），accountId 为业务关联列（一个买家一本簿）。
     *
     * @param accountId 归属买家账号 ID
     * @return 空地址簿
     */
    public AddressBook createBook(Long accountId) {
        BusinessAssert.assertNonNull(accountId, "买家账号 ID 不能为空");
        return new AddressBook(IDUtils.generateID(), accountId);
    }

    /**
     * 创建地址（ID 由雪花算法生成；bookId 取自簿主键）。
     *
     * @param book      归属地址簿（bookId 取自簿）
     * @param recipient 收件人
     * @param phone     联系电话
     * @param province  省份
     * @param city      城市
     * @param district  区县
     * @param detail    详细地址
     * @param isDefault 是否默认地址
     * @return 新地址
     */
    public Address createAddress(AddressBook book, String recipient, String phone,
                                 String province, String city, String district, String detail,
                                 boolean isDefault) {
        BusinessAssert.assertNonNull(book, "地址簿不能为空");
        assertFields(recipient, phone, province, city, district, detail);
        return new Address(IDUtils.generateID(), book.getId(),
                recipient, phone, province, city, district, detail, isDefault);
    }

    /**
     * 校验地址字段形态（新增与编辑共用；必填校验在 API 层已由 JSR-380 兜底，
     * 领域层再断言一次防止绕过 API 的直接调用路径）。
     *
     * @param recipient 收件人
     * @param phone     联系电话
     * @param province  省份
     * @param city      城市
     * @param district  区县
     * @param detail    详细地址
     */
    static void assertFields(String recipient, String phone, String province,
                             String city, String district, String detail) {
        BusinessAssert.assertTrue(StringUtils.isNotBlank(recipient), "收件人不能为空");
        BusinessAssert.assertTrue(StringUtils.isNotBlank(phone), "联系电话不能为空");
        BusinessAssert.assertTrue(StringUtils.isNotBlank(province), "省份不能为空");
        BusinessAssert.assertTrue(StringUtils.isNotBlank(city), "城市不能为空");
        BusinessAssert.assertTrue(StringUtils.isNotBlank(district), "区县不能为空");
        BusinessAssert.assertTrue(StringUtils.isNotBlank(detail), "详细地址不能为空");
    }
}