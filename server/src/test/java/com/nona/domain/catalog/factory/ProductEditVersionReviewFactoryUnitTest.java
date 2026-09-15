package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.EditVersionTriggerType;
import com.nona.domain.catalog.entity.ProductEditVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审核结论版本工厂契约测试：审核通过/驳回结论行的创建入口——
 * 触发类型定型（REVIEW_PASS / REJECT）+ 行 ID 生成，驳回行携原因。
 * <p>
 * 红状态说明：工厂结论创建方法为设计契约（方法体抛
 * UnsupportedOperationException）——本文件全部用例红，红因 = 实现缺失；
 * 编辑/回滚工厂方法为绿底座。
 *
 * @author nona9961
 */
class ProductEditVersionReviewFactoryUnitTest {

    /**
     * 归属商品 ID
     */
    private static final long PRODUCT_ID = 91001L;

    /**
     * 快照 JSON 样例（审核时刻生效内容形态）
     */
    private static final String SNAPSHOT = "{\"name\":\"在售商品\",\"images\":[],"
            + "\"attributes\":[],\"specTemplate\":null,\"skus\":[]}";

    /**
     * 被测工厂
     */
    private final ProductEditVersionFactory factory = new ProductEditVersionFactory();

    /**
     * happy：审核通过结论行创建——触发类型 REVIEW_PASS、操作人 = 审核人、
     * 无驳回原因。
     */
    @Test
    @DisplayName("审核通过结论行工厂创建")
    void createReviewPass_conclusionInPlace() {
        final ProductEditVersion version =
                factory.createReviewPass(PRODUCT_ID, 3, SNAPSHOT, "9001");

        assertThat(version.getId()).isNotNull();
        assertThat(version.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(version.getVersionNo()).isEqualTo(3);
        assertThat(version.getSnapshotJson()).isEqualTo(SNAPSHOT);
        assertThat(version.getOperator()).isEqualTo("9001");
        assertThat(version.getTriggerType()).isEqualTo(EditVersionTriggerType.REVIEW_PASS);
        assertThat(version.getReviewReason()).isNull();
    }

    /**
     * happy：审核驳回结论行创建——触发类型 REJECT、驳回原因随行承载。
     */
    @Test
    @DisplayName("审核驳回结论行工厂创建携原因")
    void createReject_reasonCarried() {
        final ProductEditVersion version =
                factory.createReject(PRODUCT_ID, 4, SNAPSHOT, "9001", "图片侵权");

        assertThat(version.getId()).isNotNull();
        assertThat(version.getTriggerType()).isEqualTo(EditVersionTriggerType.REJECT);
        assertThat(version.getReviewReason()).isEqualTo("图片侵权");
        assertThat(version.getOperator()).isEqualTo("9001");
    }
}