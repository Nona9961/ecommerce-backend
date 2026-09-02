package com.nona.inf.persistence.converters;

import com.nona.domain.identity.entity.Address;
import com.nona.inf.persistence.po.identity.AddressPO;
import org.springframework.stereotype.Component;

/**
 * 地址实体 ↔ 地址 PO 转换器（address 表单行映射，字段一一对应）。
 * <p>
 * 使用简单实体转换器契约（PoConverter）：Address 是地址簿聚合内的子实体，
 * 行级读写不需要聚合上下文参数。
 *
 * @author nona9961
 */
@Component
public class AddressConvertor implements PoConverter<Address, AddressPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<Address> domainClass() {
        return Address.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<AddressPO> poClass() {
        return AddressPO.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳（createTime/updateTime）由 JPA auditing 在持久化时填充，转换不负责。
     */
    @Override
    public AddressPO toPO(Address domain) {
        final AddressPO po = new AddressPO();
        po.setId(domain.getId());
        po.setBookId(domain.getBookId());
        po.setRecipient(domain.getRecipient());
        po.setPhone(domain.getPhone());
        po.setProvince(domain.getProvince());
        po.setCity(domain.getCity());
        po.setDistrict(domain.getDistrict());
        po.setDetail(domain.getDetail());
        po.setIsDefault(domain.isDefault());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Address toDomain(AddressPO po) {
        return new Address(po.getId(), po.getBookId(), po.getRecipient(), po.getPhone(),
                po.getProvince(), po.getCity(), po.getDistrict(), po.getDetail(),
                Boolean.TRUE.equals(po.getIsDefault()));
    }
}