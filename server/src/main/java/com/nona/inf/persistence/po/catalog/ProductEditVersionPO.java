package com.nona.inf.persistence.po.catalog;

import com.nona.domain.catalog.entity.EditVersionTriggerType;
import com.nona.inf.persistence.po.TenantScopedBasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 商品编辑版本持久化对象（product_edit_version 表，tenant=shopId）：Product
 * 聚合的 append-only 从表行（编辑留痕 + 回滚素材 + 审核结论挂点）。
 * <p>
 * 商家维度数据归店铺租户（tenant_id=shopId），天然 fail-closed 隔离——
 * 跨店铺版本行在 Hibernate 租户过滤层即被拦截。product_id 为业务关联列
 * （rootId 关联 product 主表）；version_no 为同商品内单调递增版本号，
 * (product_id, version_no) 唯一约束兜底并发重复插入（MAX+1 分配的 DB
 * 级防线）；snapshot_json 为保存时刻聚合全部内容的全量 JSON 快照
 * （体量小，全量优于增量 patch）；operator 为操作人（认证上下文身份
 * 标识）；trigger_type 为触发类型（EDIT / ROLLBACK / REVIEW_PASS /
 * REJECT；审核结论行另以 review_reason 承载驳回原因）。创建时间承载
 * 版本产生时间（create_time 审计列），版本行只增不改（无 update 路径）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "product_edit_version", uniqueConstraints = {
        @UniqueConstraint(name = "uk_product_edit_version_no",
                columnNames = {"product_id", "version_no"})
}, indexes = {
        @Index(name = "idx_product_edit_version_product", columnList = "product_id")
})
public class ProductEditVersionPO extends TenantScopedBasePO {

    /**
     * 归属商品 ID（rootId 关联）
     */
    @Column(nullable = false, name = "product_id")
    private Long productId;

    /**
     * 版本号（同商品内单调递增，从 1 起）
     */
    @Column(nullable = false, name = "version_no")
    private Integer versionNo;

    /**
     * 全量内容快照 JSON（保存时刻聚合全部内容：主体+图片+属性+规格模板+SKU）
     * <p>
     * {@code @JdbcTypeCode(LONGVARCHAR)}（Hibernate 6 官方替代）：@Lob 在
     * MySQL 方言导出 tinytext（255B 上限），与全量快照体量冲突（实测写入
     * Data truncation）；LONGVARCHAR 在 MySQL 方言 = longtext，Hibernate
     * validate 期望与表列同型（V1.2 对齐落库）。
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, name = "snapshot_json")
    private String snapshotJson;

    /**
     * 操作人（认证上下文身份标识）
     */
    @Column(nullable = false, length = 64)
    private String operator;

    /**
     * 触发类型（EDIT / ROLLBACK / REVIEW_PASS / REJECT）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, name = "trigger_type")
    private EditVersionTriggerType triggerType;

    /**
     * 审核驳回原因（可空：仅 trigger_type=REJECT 的审核结论行承载驳回
     * 原因；其余触发类型行为 null——一致性由实体构造路径校验）
     */
    @Column(length = 512, name = "review_reason")
    private String reviewReason;

    /**
     * 归属商品 ID。
     *
     * @return 商品 ID
     */
    public Long getProductId() {
        return productId;
    }

    /**
     * 设置归属商品 ID。
     *
     * @param productId 商品 ID
     */
    public void setProductId(Long productId) {
        this.productId = productId;
    }

    /**
     * 版本号。
     *
     * @return 版本号
     */
    public Integer getVersionNo() {
        return versionNo;
    }

    /**
     * 设置版本号。
     *
     * @param versionNo 版本号
     */
    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    /**
     * 全量内容快照 JSON。
     *
     * @return 快照 JSON
     */
    public String getSnapshotJson() {
        return snapshotJson;
    }

    /**
     * 设置快照 JSON。
     *
     * @param snapshotJson 快照 JSON
     */
    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }

    /**
     * 操作人。
     *
     * @return 操作人身份标识
     */
    public String getOperator() {
        return operator;
    }

    /**
     * 设置操作人。
     *
     * @param operator 操作人
     */
    public void setOperator(String operator) {
        this.operator = operator;
    }

    /**
     * 触发类型。
     *
     * @return 触发类型
     */
    public EditVersionTriggerType getTriggerType() {
        return triggerType;
    }

    /**
     * 设置触发类型。
     *
     * @param triggerType 触发类型
     */
    public void setTriggerType(EditVersionTriggerType triggerType) {
        this.triggerType = triggerType;
    }

    /**
     * 审核驳回原因。
     *
     * @return 驳回原因；非 REJECT 行/未驳回为 null
     */
    public String getReviewReason() {
        return reviewReason;
    }

    /**
     * 设置审核驳回原因。
     *
     * @param reviewReason 驳回原因；null=无驳回原因
     */
    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }
}