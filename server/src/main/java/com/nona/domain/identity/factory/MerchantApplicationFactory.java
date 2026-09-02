package com.nona.domain.identity.factory;

import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.domain.identity.entity.MerchantApplication;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;
import com.nona.util.IDUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 入驻申请工厂：申请的创建入口（ID 生成 + 初始状态 + 资料形态校验）。
 * <p>
 * 创建即进入待审状态（提交语义）；ID 经 {@link IDUtils#generateID()} 生成，
 * 禁止手动赋值；资料字段（店铺名/联系人/联系方式）非空在此防御校验，
 * 防止绕过 API 的直接调用路径。同一账号至多一个 pending 申请的
 * 不变量由用例层（提交前查重）+ 数据库 account_id 唯一约束共同承载。
 *
 * @author nona9961
 */
@Component
public class MerchantApplicationFactory {

    /**
     * 创建入驻申请（初始状态 pending）。
     *
     * @param accountId    提交实体（商家账号 ID）
     * @param shopName     店铺名
     * @param contactName  联系人
     * @param contactPhone 联系方式
     * @return 新建申请（提交时间由持久化层回填）
     */
    public MerchantApplication create(Long accountId, String shopName, String contactName, String contactPhone) {
        BusinessAssert.assertNonNull(accountId, "账号 ID 不能为空");
        if (StringUtils.isBlank(shopName)) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code(), "店铺名不能为空", 400);
        }
        if (StringUtils.isBlank(contactName)) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code(), "联系人不能为空", 400);
        }
        if (StringUtils.isBlank(contactPhone)) {
            throw new BusinessException(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code(), "联系方式不能为空", 400);
        }
        return new MerchantApplication(IDUtils.generateID(), accountId, shopName.trim(),
                contactName.trim(), contactPhone.trim(), ApplicationStatus.PENDING,
                null, null, null, null);
    }
}