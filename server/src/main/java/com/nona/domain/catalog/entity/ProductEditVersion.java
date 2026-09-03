package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 商品编辑版本记录（product_edit_version 从表行，append-only 实体）：
 * Product 聚合的编辑留痕单元——每次保存/回滚在版本链上追加一行，
 * 承载版本号（同商品内单调递增）、触发类型、操作人与全量内容快照
 * （snapshot_json，内容留痕 + 回滚素材双重语义，属后续阶段审核结论
 * 的承载挂点）。
 * <p>
 * 领域归属：Product 聚合的内实体（版本链写入与聚合保存同事务，由用例层
 * 编排）；但历史查询（按商品分页读取）经版本仓储按商品分页读取，永远不装配进
 * 聚合内存——append-only 记录行无聚合内变更路径，本实体无任何 setter，
 * 对外仅暴露只读访问器。
 * <p>
 * 装配约束（构造路径校验，与持久化加载共用）：版本号必须为正、快照
 * JSON 与操作人必填非空、触发类型必填、归属商品必填——非法形态的版本
 * 行无业务意义（版本链恒等式：版本号严格递增由分配方保证，唯一性由
 * DB 约束兜底）。
 *
 * @author nona9961
 */
public class ProductEditVersion {

    /**
     * 行 ID（Snowflake，本表独立主键）
     */
    private final Long id;

    /**
     * 归属商品 ID（rootId 关联 product 主表，创建时定型不可变）
     */
    private final Long productId;

    /**
     * 版本号（同商品内单调递增，从 1 起）
     */
    private final int versionNo;

    /**
     * 全量内容快照（保存时刻聚合全部内容的 JSON 序列化）
     */
    private final String snapshotJson;

    /**
     * 操作人（认证上下文身份标识）
     */
    private final String operator;

    /**
     * 触发类型（EDIT / ROLLBACK；REVIEW_PASS / REJECT 属后续阶段）
     */
    private final EditVersionTriggerType triggerType;

    /**
     * 版本产生时间（持久化审计填充；新建路径尚未落库时为 null）
     */
    private final LocalDateTime createdAt;

    /**
     * 构造新建版本（落库前形态）：产生时间由持久化审计填充（null）。
     *
     * @param id           行 ID
     * @param productId    归属商品 ID
     * @param versionNo    版本号（正整数）
     * @param snapshotJson 全量内容快照 JSON（必填非空）
     * @param operator     操作人（必填非空）
     * @param triggerType  触发类型（必填）
     */
    public ProductEditVersion(Long id, Long productId, int versionNo,
                              String snapshotJson, String operator,
                              EditVersionTriggerType triggerType) {
        this(id, productId, versionNo, snapshotJson, operator, triggerType, null);
    }

    /**
     * 构造版本记录（完整形态）：字段校验与 {@link #ProductEditVersion(Long, Long, int, String, String, EditVersionTriggerType)}
     * 一致；createdAt 为持久化读回值（null=新建路径尚未落库）。
     *
     * @param id           行 ID
     * @param productId    归属商品 ID
     * @param versionNo    版本号（正整数）
     * @param snapshotJson 全量内容快照 JSON（必填非空）
     * @param operator     操作人（必填非空）
     * @param triggerType  触发类型（必填）
     * @param createdAt    版本产生时间（读回路径由审计列填充）
     */
    public ProductEditVersion(Long id, Long productId, int versionNo,
                              String snapshotJson, String operator,
                              EditVersionTriggerType triggerType, LocalDateTime createdAt) {
        if (productId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code(), "版本归属商品不能为空");
        }
        if (versionNo <= 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code(), "版本号必须为正整数");
        }
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code(), "版本快照不能为空");
        }
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code(), "版本操作人不能为空");
        }
        if (triggerType == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code(), "版本触发类型不能为空");
        }
        this.id = id;
        this.productId = productId;
        this.versionNo = versionNo;
        this.snapshotJson = snapshotJson;
        this.operator = operator;
        this.triggerType = triggerType;
        this.createdAt = createdAt;
    }

    /**
     * 行 ID。
     *
     * @return 行 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 归属商品 ID。
     *
     * @return 商品 ID
     */
    public Long getProductId() {
        return productId;
    }

    /**
     * 版本号。
     *
     * @return 版本号（同商品内单调递增）
     */
    public int getVersionNo() {
        return versionNo;
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
     * 操作人。
     *
     * @return 操作人身份标识
     */
    public String getOperator() {
        return operator;
    }

    /**
     * 触发类型。
     *
     * @return 触发类型（EDIT / ROLLBACK；REVIEW_PASS / REJECT 属后续阶段）
     */
    public EditVersionTriggerType getTriggerType() {
        return triggerType;
    }

    /**
     * 版本产生时间。
     *
     * @return 产生时间；新建路径尚未落库为 null
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 版本归属一致判定（回滚目标版本必须属于目标商品——按不存在呈现的
     * 防御性兜底，与商品子实体访问语义一致）。
     *
     * @param productId 商品 ID
     * @return 归属一致返回 true
     */
    public boolean belongsTo(Long productId) {
        return Objects.equals(this.productId, productId);
    }
}