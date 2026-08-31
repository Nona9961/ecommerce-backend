package com.nona.inf.persistence.converters;

import com.nona.domain.identity.entity.Account;
import com.nona.inf.persistence.po.identity.AccountPO;
import org.springframework.stereotype.Component;

/**
 * 账号聚合根 ↔ 账号 PO 转换器（单表 {@code account}，字段一一对应，
 * 无子对象，不需要 other 辅助参数）。
 *
 * @author nona9961
 */
@Component
public class AccountConvertor extends AbstractConvertor<Account, AccountPO, Void> {

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳（createTime/updateTime）由 JPA auditing 在持久化时填充，转换不负责。
     */
    @Override
    protected AccountPO safedConvertToPO(Account root) {
        final AccountPO po = new AccountPO();
        po.setId(root.getId());
        po.setType(root.getType());
        po.setUsername(root.getUsername());
        po.setPasswordHash(root.getPasswordHash());
        po.setStatus(root.getStatus());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Account safedConvertToRoot(AccountPO po, Void other) {
        return new Account(po.getId(), po.getUsername(), po.getPasswordHash(), po.getStatus(), po.getType());
    }
}
