package com.nona.inf.persistence.po.catalog;

import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.inf.persistence.po.TenantScopedBasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 商品持久化对象（product 表，tenant=shopId）：Product 聚合根主表。
 * <p>
 * 商家维度数据归店铺租户（tenant_id=shopId），天然 fail-closed 隔离——
 * 跨店铺访问商品行在 Hibernate 租户过滤层即被拦截。shop_id 为业务关联列
 * （rootId 关联 shop 主表，冗余承载归属便于店铺维度分页查询）；平台类目/
 * 品牌为可空引用列（category_id / brand_id，草稿允许不挂载）；status 为
 * 生命周期状态（一期恒 DRAFT——草稿可保存不生效，状态机属后续阶段）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "product", indexes = {
        @Index(name = "idx_product_shop", columnList = "shop_id")
})
public class ProductPO extends TenantScopedBasePO {

    /**
     * 所属店铺 ID（rootId 关联）
     */
    @Column(nullable = false, name = "shop_id")
    private Long shopId;

    /**
     * 商品名称
     */
    @Column(nullable = false, length = 128)
    private String name;

    /**
     * 商品描述（可空）
     */
    @Column(length = 4000)
    private String description;

    /**
     * 平台类目 ID（可空引用）
     */
    @Column(name = "category_id")
    private Long categoryId;

    /**
     * 品牌 ID（可空引用）
     */
    @Column(name = "brand_id")
    private Long brandId;

    /**
     * 商品状态（一期恒 DRAFT）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProductStatus status;

    /**
     * 规格模板 JSON（可空=尚未配置模板；空数组=空模板）：规格模板为聚合
     * 内值对象（整体替换语义），以 JSON 载体随主表行走——结构序列化/
     * 反序列化由转换器经中间形态完成（specTemplate_json 列与 JSON 扩展
     * 列同形态）。
     * <p>
     * WU-53：@Lob → @JdbcTypeCode(LONGVARCHAR)（同 snapshot_json，见
     * ProductEditVersionPO 注释：@Lob 导出 tinytext 255B 不敷使用）。
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "spec_template_json")
    private String specTemplateJson;

    /**
     * 待审草稿 JSON（可空=无待审草稿）：敏感字段编辑分流后的新内容暂存
     * 位（字段级分流承载）——与生效内容分离，审核通过后覆盖正式
     * 内容、驳回后作废；序列化形态与版本快照一致（快照中间形态复用），
     * 由转换器双向转换。
     * <p>
     * WU-53：@Lob → @JdbcTypeCode(LONGVARCHAR)（同 snapshot_json）。
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "pending_draft_json")
    private String pendingDraftJson;

    /**
     * 运费模板 ID（可空引用列）：商品级绑定店铺运费模板（S9.2 商品绑
     * 模板）——绑/解绑经商品聚合（写面冻结守卫），目标存在性/归属校验
     * 在用例层；null=未绑定（详情运费区按无模板呈现）。
     */
    @Column(name = "freight_template_id")
    private Long freightTemplateId;

    /**
     * 所属店铺 ID。
     *
     * @return 店铺 ID
     */
    public Long getShopId() {
        return shopId;
    }

    /**
     * 设置所属店铺 ID。
     *
     * @param shopId 店铺 ID
     */
    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    /**
     * 商品名称。
     *
     * @return 名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置商品名称。
     *
     * @param name 名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 商品描述。
     *
     * @return 描述；未填写为 null
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置商品描述。
     *
     * @param description 描述
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 平台类目 ID。
     *
     * @return 类目 ID；未挂载为 null
     */
    public Long getCategoryId() {
        return categoryId;
    }

    /**
     * 设置平台类目 ID。
     *
     * @param categoryId 类目 ID
     */
    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    /**
     * 品牌 ID。
     *
     * @return 品牌 ID；未挂载为 null
     */
    public Long getBrandId() {
        return brandId;
    }

    /**
     * 设置品牌 ID。
     *
     * @param brandId 品牌 ID
     */
    public void setBrandId(Long brandId) {
        this.brandId = brandId;
    }

    /**
     * 商品状态。
     *
     * @return 状态
     */
    public ProductStatus getStatus() {
        return status;
    }

    /**
     * 设置商品状态。
     *
     * @param status 状态
     */
    public void setStatus(ProductStatus status) {
        this.status = status;
    }

    /**
     * 规格模板 JSON 内容。
     *
     * @return JSON 字符串；未配置模板返回 null
     */
    public String getSpecTemplateJson() {
        return specTemplateJson;
    }

    /**
     * 设置规格模板 JSON 内容。
     *
     * @param specTemplateJson JSON 字符串；null=未配置模板
     */
    public void setSpecTemplateJson(String specTemplateJson) {
        this.specTemplateJson = specTemplateJson;
    }

    /**
     * 待审草稿 JSON 内容。
     *
     * @return JSON 字符串；无待审草稿返回 null
     */
    public String getPendingDraftJson() {
        return pendingDraftJson;
    }

    /**
     * 设置待审草稿 JSON 内容。
     *
     * @param pendingDraftJson JSON 字符串；null=无待审草稿
     */
    public void setPendingDraftJson(String pendingDraftJson) {
        this.pendingDraftJson = pendingDraftJson;
    }

    /**
     * 运费模板 ID。
     *
     * @return 模板 ID；未绑定返回 null
     */
    public Long getFreightTemplateId() {
        return freightTemplateId;
    }

    /**
     * 设置运费模板 ID。
     *
     * @param freightTemplateId 模板 ID；null=未绑定
     */
    public void setFreightTemplateId(Long freightTemplateId) {
        this.freightTemplateId = freightTemplateId;
    }
}