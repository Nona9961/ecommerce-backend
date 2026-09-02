package com.nona.inf.persistence.converters;

import com.nona.domain.identity.entity.Address;
import com.nona.domain.identity.entity.AddressBook;
import com.nona.inf.persistence.po.identity.AddressBookPO;
import com.nona.inf.persistence.po.identity.AddressPO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 地址簿聚合根 ↔ 地址簿 PO 转换器（主表 address_book + 从表 address 行集合）。
 * <p>
 * 主表行 = 聚合根身份（id = 簿独立主键，accountId 为业务关联列）；从表行经
 * {@link AddressConvertor} 逐行转换后由聚合方法装载（add 保持顺序与默认标记）。
 * other 参数为从表行集合（读路径由仓储 getOther 提供）。
 *
 * @author nona9961
 */
@Component
public class AddressBookConvertor extends AbstractConvertor<AddressBook, AddressBookPO, List<AddressPO>> {

    /**
     * 地址行转换器
     */
    private final AddressConvertor addressConvertor;

    /**
     * 构造地址簿转换器。
     *
     * @param addressConvertor 地址行转换器
     */
    public AddressBookConvertor(AddressConvertor addressConvertor) {
        this.addressConvertor = addressConvertor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 主表行承载聚合根身份（id = 簿主键）与业务关联（accountId），
     * 审计时间戳由 JPA auditing 填充。
     */
    @Override
    protected AddressBookPO safedConvertToPO(AddressBook root) {
        final AddressBookPO po = new AddressBookPO();
        po.setId(root.getId());
        po.setAccountId(root.getAccountId());
        return po;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从表行逐个经 {@link AddressConvertor} 转换并装载（顺序保持加载序，
     * 默认标记由数据保证至多一行为 true）。
     */
    @Override
    protected AddressBook safedConvertToRoot(AddressBookPO po, List<AddressPO> addressPOs) {
        final AddressBook book = new AddressBook(po.getId(), po.getAccountId());
        for (final AddressPO addressPO : addressPOs) {
            book.add(addressConvertor.toDomain(addressPO));
        }
        return book;
    }
}