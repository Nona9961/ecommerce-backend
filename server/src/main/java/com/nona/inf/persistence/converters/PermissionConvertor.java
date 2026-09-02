package com.nona.inf.persistence.converters;

import com.nona.domain.identity.entity.Permission;
import com.nona.inf.persistence.po.identity.PermissionPO;
import org.springframework.stereotype.Component;

/**
 * 权限点实体 ↔ PO 转换器（单表 permission，字段一一对应，无子对象）。
 *
 * @author nona9961
 */
@Component
public class PermissionConvertor extends AbstractConvertor<Permission, PermissionPO, Void> {

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳（createTime/updateTime）由 JPA auditing 在持久化时填充，转换不负责。
     */
    @Override
    protected PermissionPO safedConvertToPO(Permission root) {
        final PermissionPO po = new PermissionPO();
        po.setId(root.getId());
        po.setCode(root.getCode());
        po.setName(root.getName());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Permission safedConvertToRoot(PermissionPO po, Void other) {
        return new Permission(po.getId(), po.getCode(), po.getName());
    }
}
