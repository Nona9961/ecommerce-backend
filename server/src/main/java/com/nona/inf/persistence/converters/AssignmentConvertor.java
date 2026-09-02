package com.nona.inf.persistence.converters;

import com.nona.domain.identity.entity.Assignment;
import com.nona.inf.persistence.po.identity.AssignmentPO;
import org.springframework.stereotype.Component;

/**
 * 账号-角色分配实体 ↔ PO 转换器（单表 assignment，字段一一对应，无子对象）。
 *
 * @author nona9961
 */
@Component
public class AssignmentConvertor extends AbstractConvertor<Assignment, AssignmentPO, Void> {

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳（createTime/updateTime）由 JPA auditing 在持久化时填充，转换不负责。
     */
    @Override
    protected AssignmentPO safedConvertToPO(Assignment root) {
        final AssignmentPO po = new AssignmentPO();
        po.setId(root.getId());
        po.setAccountId(root.getAccountId());
        po.setRoleId(root.getRoleId());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Assignment safedConvertToRoot(AssignmentPO po, Void other) {
        return new Assignment(po.getId(), po.getAccountId(), po.getRoleId());
    }
}
