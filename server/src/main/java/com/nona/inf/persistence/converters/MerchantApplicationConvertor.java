package com.nona.inf.persistence.converters;

import com.nona.domain.identity.entity.MerchantApplication;
import com.nona.inf.persistence.po.identity.MerchantApplicationPO;
import org.springframework.stereotype.Component;

/**
 * 入驻申请 ↔ 入驻申请 PO 转换器（onboarding_application 表字段一一对应，
 * 无子对象，不需要 other 辅助参数）。
 * <p>
 * 审计时间戳（createTime/updateTime）由 JPA auditing 在持久化时填充，
 * 转换不负责；提交时间（createTime）由读取侧从 PO 回填进领域申请。
 *
 * @author nona9961
 */
@Component
public class MerchantApplicationConvertor
        extends AbstractConvertor<MerchantApplication, MerchantApplicationPO, Void> {

    /**
     * {@inheritDoc}
     */
    @Override
    protected MerchantApplicationPO safedConvertToPO(MerchantApplication root) {
        final MerchantApplicationPO po = new MerchantApplicationPO();
        po.setId(root.getId());
        po.setAccountId(root.getAccountId());
        po.setShopName(root.getShopName());
        po.setContactName(root.getContactName());
        po.setContactPhone(root.getContactPhone());
        po.setStatus(root.getStatus());
        po.setRejectReason(root.getRejectReason());
        po.setReviewerId(root.getReviewerId());
        po.setReviewTime(root.getReviewTime());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected MerchantApplication safedConvertToRoot(MerchantApplicationPO po, Void other) {
        return new MerchantApplication(po.getId(), po.getAccountId(), po.getShopName(),
                po.getContactName(), po.getContactPhone(), po.getStatus(),
                po.getRejectReason(), po.getReviewerId(), po.getReviewTime(), po.getCreateTime());
    }
}