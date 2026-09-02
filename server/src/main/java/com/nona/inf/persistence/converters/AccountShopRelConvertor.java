package com.nona.inf.persistence.converters;

import com.nona.domain.identity.entity.AccountShopRel;
import com.nona.inf.persistence.po.identity.AccountShopRelPO;
import org.springframework.stereotype.Component;

/**
 * 账号-店铺关联实体 ↔ PO 转换器（单表 account_shop_rel，字段一一对应，
 * 无子对象，不需要 other 辅助参数）。
 *
 * @author nona9961
 */
@Component
public class AccountShopRelConvertor extends AbstractConvertor<AccountShopRel, AccountShopRelPO, Void> {

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳（createTime/updateTime）由 JPA auditing 在持久化时填充，转换不负责。
     */
    @Override
    protected AccountShopRelPO safedConvertToPO(AccountShopRel root) {
        final AccountShopRelPO po = new AccountShopRelPO();
        po.setId(root.getId());
        po.setAccountId(root.getAccountId());
        po.setShopId(root.getShopId());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected AccountShopRel safedConvertToRoot(AccountShopRelPO po, Void other) {
        return new AccountShopRel(po.getId(), po.getAccountId(), po.getShopId());
    }
}