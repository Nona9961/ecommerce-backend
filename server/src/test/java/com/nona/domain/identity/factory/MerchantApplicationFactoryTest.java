package com.nona.domain.identity.factory;

import com.nona.domain.identity.entity.ApplicationStatus;
import com.nona.domain.identity.entity.MerchantApplication;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入驻申请工厂单元测试：申请创建（ID 生成 + 初始状态 pending）与资料形态校验。
 * <p>
 * 创建即进入待审状态（提交语义）；ID 经工具生成，禁止手动赋值；
 * 资料字段（店铺名/联系人/联系方式）非空由工厂防御校验，防止绕过 API 直接调用。
 *
 * @author nona9961
 */
class MerchantApplicationFactoryTest {

    /**
     * 被测工厂
     */
    private final MerchantApplicationFactory factory = new MerchantApplicationFactory();

    /**
     * happy：创建申请——ID 生成、归属账号、资料与初始状态 pending 正确。
     */
    @Test
    @DisplayName("创建申请进入待审")
    void create_submittedApplicationPending() {
        final MerchantApplication application = factory.create(10001L, "示例店铺", "张三", "13800138000");

        assertThat(application.getId()).isNotNull();
        assertThat(application.getAccountId()).isEqualTo(10001L);
        assertThat(application.getShopName()).isEqualTo("示例店铺");
        assertThat(application.getContactName()).isEqualTo("张三");
        assertThat(application.getContactPhone()).isEqualTo("13800138000");
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(application.getRejectReason()).isNull();
        assertThat(application.getReviewerId()).isNull();
        assertThat(application.getReviewTime()).isNull();
    }

    /**
     * error：账号 ID 为空拒绝（提交实体不变量）。
     */
    @Test
    @DisplayName("空账号拒绝")
    void create_nullAccountId_rejects() {
        assertThatThrownBy(() -> factory.create(null, "示例店铺", "张三", "13800138000"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号");
    }

    /**
     * error：空白店铺名拒绝。
     */
    @Test
    @DisplayName("空白店铺名拒绝")
    void create_blankShopName_rejects() {
        assertThatThrownBy(() -> factory.create(10001L, "  ", "张三", "13800138000"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：空白联系人拒绝。
     */
    @Test
    @DisplayName("空白联系人拒绝")
    void create_blankContactName_rejects() {
        assertThatThrownBy(() -> factory.create(10001L, "示例店铺", "", "13800138000"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：空白联系方式拒绝。
     */
    @Test
    @DisplayName("空白联系方式拒绝")
    void create_blankContactPhone_rejects() {
        assertThatThrownBy(() -> factory.create(10001L, "示例店铺", "张三", null))
                .isInstanceOf(BusinessException.class);
    }
}