package com.nona.inf.persistence.converters;

import com.nona.domain.identity.entity.Role;
import com.nona.inf.persistence.po.identity.RolePO;
import org.springframework.stereotype.Component;

/**
 * 角色实体 ↔ PO 转换器（单表 role，字段一一对应，无子对象）。
 *
 * @author nona9961
 */
@Component
public class RoleConvertor extends AbstractConvertor<Role, RolePO, Void> {

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳（createTime/updateTime）由 JPA auditing 在持久化时填充，转换不负责。
     */
    @Override
    protected RolePO safedConvertToPO(Role root) {
        final RolePO po = new RolePO();
        po.setId(root.getId());
        po.setCode(root.getCode());
        po.setName(root.getName());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Role safedConvertToRoot(RolePO po, Void other) {
        return new Role(po.getId(), po.getCode(), po.getName());
    }
}
