package com.nona.api.common;

/**
 * 入驻申请状态（商家端查看 / 平台端审核列表与审核动作共享的契约枚举）。
 * <p>
 * 与身份域 {@link com.nona.domain.identity.entity.ApplicationStatus} 一一对应，
 * 映射点收敛在各端用例（enum → enum 显式 switch），契约演进不侵入领域。
 *
 * @author nona9961
 */
public enum OnboardingStatus {

    /**
     * 待审核：提交后进入，平台审核动作的合法前置状态。
     */
    PENDING,

    /**
     * 已通过：终态，审核通过后将创建店铺（店铺创建编排在后续版本接入）。
     */
    APPROVED,

    /**
     * 已驳回：附驳回原因，商家可修改后重提。
     */
    REJECTED;

    /**
     * 按枚举名解析查询参数中的申请状态；null 或未知值拒绝。
     *
     * @param name 枚举名（如 PENDING）；null 或未知值拒绝
     * @return 匹配的申请状态
     * @throws com.nona.exceptions.BusinessException 非法状态（400 generic.validation_failed）
     */
    public static OnboardingStatus fromName(String name) {
        for (OnboardingStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new com.nona.exceptions.BusinessException(
                com.nona.exceptions.BusinessCode.VALIDATION_FAILED.code(), "非法入驻申请状态", 400);
    }
}