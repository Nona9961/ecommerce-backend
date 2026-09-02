package com.nona.domain.identity.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入驻申请聚合单元测试：状态机（pending → approve/reject，rejected 可编辑重提）
 * 与关键不变量（approved 不可变、驳回原因必填、重提清空审核字段）的成功与失败路径。
 * <p>
 * 覆盖：happy（编辑/重提/通过/驳回/重提后再通过）、critical（重提清空旧审核结论、
 * approve 与 reject 后字段落位）、error（非 pending 审核、非 rejected 重提、
 * approved 编辑、空驳回原因）。
 *
 * @author nona9961
 */
class MerchantApplicationTest {

    /**
     * 固定申请 ID（聚合逻辑不依赖具体主键）
     */
    private static final long APP_ID = 1L;

    /**
     * 固定提交实体（商家账号）
     */
    private static final long ACCOUNT_ID = 10001L;

    /**
     * 固定审核人（平台运营账号）
     */
    private static final long REVIEWER = 9001L;

    /**
     * 合法资料（店铺名/联系人/联系方式）
     */
    private static final String SHOP = "示例店铺";

    /**
     * 合法联系人
     */
    private static final String CONTACT = "张三";

    /**
     * 合法电话
     */
    private static final String PHONE = "13800138000";

    /**
     * 构造待审申请（状态 pending，模拟工厂创建结果）。
     *
     * @return 待审申请
     */
    private static MerchantApplication pending() {
        return new MerchantApplication(APP_ID, ACCOUNT_ID, SHOP, CONTACT, PHONE,
                ApplicationStatus.PENDING, null, null, null, null);
    }

    /**
     * 构造已驳回申请（带驳回原因与审核记录）。
     *
     * @return 已驳回申请
     */
    private static MerchantApplication rejected() {
        return new MerchantApplication(APP_ID, ACCOUNT_ID, SHOP, CONTACT, PHONE,
                ApplicationStatus.REJECTED, "资料不完整", REVIEWER, java.time.LocalDateTime.now(), null);
    }

    /**
     * happy：pending 下编辑资料成功，状态保持待审。
     */
    @Test
    @DisplayName("待审状态可编辑资料")
    void editMaterials_pending_updatesFields() {
        final MerchantApplication application = pending();

        application.editMaterials("新店名", "李四", "13900139000");

        assertThat(application.getShopName()).isEqualTo("新店名");
        assertThat(application.getContactName()).isEqualTo("李四");
        assertThat(application.getContactPhone()).isEqualTo("13900139000");
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PENDING);
    }

    /**
     * happy：rejected 下编辑资料成功（驳回原因保留，等待重提）。
     */
    @Test
    @DisplayName("驳回后可编辑资料")
    void editMaterials_rejected_updatesFields() {
        final MerchantApplication application = rejected();

        application.editMaterials("新店名", "李四", "13900139000");

        assertThat(application.getShopName()).isEqualTo("新店名");
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(application.getRejectReason()).isEqualTo("资料不完整");
        assertThat(application.getReviewerId()).isEqualTo(REVIEWER);
    }

    /**
     * error：approved 后不可变——编辑资料拒绝。
     */
    @Test
    @DisplayName("已通过申请编辑资料拒绝")
    void editMaterials_approved_rejects() {
        final MerchantApplication application = pending();
        application.approve(REVIEWER);

        assertThatThrownBy(() -> application.editMaterials("新店名", "李四", "13900139000"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可修改");
    }

    /**
     * error：空白资料编辑拒绝（防御校验，防绕过 API 直接调用）。
     */
    @Test
    @DisplayName("空白资料编辑拒绝")
    void editMaterials_blankFields_rejects() {
        final MerchantApplication application = pending();

        assertThatThrownBy(() -> application.editMaterials("", CONTACT, PHONE))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * happy：approve 通过——状态迁移 approved，审核人/时间落位，驳回原因为空。
     */
    @Test
    @DisplayName("审核通过状态迁移")
    void approve_pending_toApproved() {
        final MerchantApplication application = pending();

        application.approve(REVIEWER);

        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(application.getReviewerId()).isEqualTo(REVIEWER);
        assertThat(application.getReviewTime()).isNotNull();
        assertThat(application.getRejectReason()).isNull();
    }

    /**
     * error：非 pending 状态重复 approve 拒绝（状态机守卫）。
     */
    @Test
    @DisplayName("重复通过拒绝")
    void approve_nonPending_rejects() {
        final MerchantApplication application = pending();
        application.approve(REVIEWER);

        assertThatThrownBy(() -> application.approve(REVIEWER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code());
    }

    /**
     * happy：reject 驳回——状态迁移 rejected，原因/审核人/时间落位。
     */
    @Test
    @DisplayName("审核驳回状态迁移")
    void reject_pending_toRejected() {
        final MerchantApplication application = pending();

        application.reject(REVIEWER, "资料不完整");

        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(application.getRejectReason()).isEqualTo("资料不完整");
        assertThat(application.getReviewerId()).isEqualTo(REVIEWER);
        assertThat(application.getReviewTime()).isNotNull();
    }

    /**
     * error：空驳回原因拒绝（驳回必须附原因）。
     */
    @Test
    @DisplayName("空驳回原因拒绝")
    void reject_blankReason_rejects() {
        final MerchantApplication application = pending();

        assertThatThrownBy(() -> application.reject(REVIEWER, "  "))
                .isInstanceOf(BusinessException.class);
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PENDING);
    }

    /**
     * error：非 pending 状态 reject 拒绝（approved 终态）。
     */
    @Test
    @DisplayName("已通过申请再驳回拒绝")
    void reject_approved_rejects() {
        final MerchantApplication application = pending();
        application.approve(REVIEWER);

        assertThatThrownBy(() -> application.reject(REVIEWER, "资料不完整"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * happy：驳回后重提——迁移回 pending，资料更新，旧审核结论清空。
     */
    @Test
    @DisplayName("驳回后重提回到待审")
    void resubmit_rejected_toPending() {
        final MerchantApplication application = rejected();

        application.resubmit("新店名", "李四", "13900139000");

        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(application.getShopName()).isEqualTo("新店名");
        assertThat(application.getContactName()).isEqualTo("李四");
        assertThat(application.getContactPhone()).isEqualTo("13900139000");
        assertThat(application.getRejectReason()).isNull();
        assertThat(application.getReviewerId()).isNull();
        assertThat(application.getReviewTime()).isNull();
    }

    /**
     * error：pending 状态重提拒绝（重提必须基于被驳回版本）。
     */
    @Test
    @DisplayName("待审状态重提拒绝")
    void resubmit_pending_rejects() {
        final MerchantApplication application = pending();

        assertThatThrownBy(() -> application.resubmit(SHOP, CONTACT, PHONE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.IDENTITY_ONBOARDING_STATE.code());
    }

    /**
     * error：approved 状态重提拒绝（终态不可变）。
     */
    @Test
    @DisplayName("已通过状态重提拒绝")
    void resubmit_approved_rejects() {
        final MerchantApplication application = pending();
        application.approve(REVIEWER);

        assertThatThrownBy(() -> application.resubmit(SHOP, CONTACT, PHONE))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * happy：完整链路——驳回 → 编辑重提 → 审核通过，终态 approved。
     */
    @Test
    @DisplayName("驳回重提后审核通过")
    void fullLifecycle_rejectThenResubmitThenApprove() {
        final MerchantApplication application = pending();
        application.reject(REVIEWER, "营业执照缺失");
        application.resubmit("新店名", "李四", "13900139000");
        application.approve(REVIEWER);

        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(application.getShopName()).isEqualTo("新店名");
        assertThat(application.getRejectReason()).isNull();
    }
}