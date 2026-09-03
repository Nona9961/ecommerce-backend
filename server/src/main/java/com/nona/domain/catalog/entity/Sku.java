package com.nona.domain.catalog.entity;

/**
 * 可售单元 SKU（Product 聚合内实体，对应 product_sku 表行）：一个具体
 * 可售变体——规格组合（specHash/specSummary 派生）+ 独立价格 + 启用状态。
 * <p>
 * SKU 无独立创建路径：只能由规格模板配置生成（{@link Product#configureSpecTemplate}
 * 按笛卡尔积展开创建/回收），生命周期绑定模板；同商品内规格组合唯一
 * （specHash 唯一，DB 唯一约束 + 聚合守卫双保险）。跨域以 SKU ID 引用
 * （订单/库存等上下文只持 skuId，不持有聚合对象引用）。
 * <p>
 * 业务不变量（收敛在 Product 聚合方法内）：
 * <ol>
 *     <li>价格可空=未定价（草稿期合法形态；提交审核时价格必填校验属
 *         后续阶段）；非空时为正整数分（0 与负数无业务意义，拒绝）；</li>
 *     <li>启用状态独立可切换（停用 SKU 不出售）；新生成的 SKU 默认停用
 *         （fail-closed：商家显式启用以避免未配价即误售）；</li>
 *     <li>本实体不提供对外变更路径（updatePrice/setEnabled 仅聚合调用）。</li>
 * </ol>
 * 持久化归属：主键独立（Snowflake），关联经 productId（rootId）指向
 * 商品主表；金额单位分（Long）。
 *
 * @author nona9961
 */
public class Sku {

    /**
     * SKU ID（Snowflake，跨域引用键）
     */
    private final Long id;

    /**
     * 归属商品 ID（rootId 关联，创建时定型不可变）
     */
    private final Long productId;

    /**
     * 规格组合规范化摘要（64 字符 hex，同商品内唯一）
     */
    private String specHash;

    /**
     * 规格组合可读摘要（配置序拼接，展示用）
     */
    private String specSummary;

    /**
     * 销售价（分；null=未定价）
     */
    private Long price;

    /**
     * 是否启用（新生成默认 false）
     */
    private boolean enabled;

    /**
     * 构造 SKU（防御：ID/归属商品/规格摘要必填非空——SKU 必是模板
     * 组合的产物，身份三要素不可缺失；价格与启用由聚合路径编排）。
     *
     * @param id          SKU ID
     * @param productId   归属商品 ID
     * @param specHash    规格组合规范化摘要
     * @param specSummary 规格组合可读摘要
     * @param price       销售价（分，可空=未定价）
     * @param enabled     是否启用
     */
    public Sku(Long id, Long productId, String specHash, String specSummary,
               Long price, boolean enabled) {
        if (id == null) {
            throw new IllegalArgumentException("SKU ID不能为空");
        }
        if (productId == null) {
            throw new IllegalArgumentException("归属商品不能为空");
        }
        if (specHash == null || specHash.isBlank()) {
            throw new IllegalArgumentException("规格摘要不能为空");
        }
        this.id = id;
        this.productId = productId;
        this.specHash = specHash;
        this.specSummary = specSummary;
        this.price = price;
        this.enabled = enabled;
    }

    /**
     * SKU ID。
     *
     * @return SKU ID
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
     * 规格组合规范化摘要。
     *
     * @return 摘要
     */
    public String getSpecHash() {
        return specHash;
    }

    /**
     * 规格组合可读摘要。
     *
     * @return 摘要
     */
    public String getSpecSummary() {
        return specSummary;
    }

    /**
     * 销售价（分）。
     *
     * @return 价格；未定价返回 null
     */
    public Long getPrice() {
        return price;
    }

    /**
     * 是否启用。
     *
     * @return 启用返回 true
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 更新价格（仅 {@link Product} 聚合的改价路径调用——可空=清除价格
     * 复位未定价；正数校验由聚合方法统一编排）。
     *
     * @param price 新价格（分，可空）
     */
    void updatePrice(Long price) {
        this.price = price;
    }

    /**
     * 切换启用状态（仅 {@link Product} 聚合的启停路径调用）。
     *
     * @param enabled 是否启用
     */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 刷新规格派生值（仅 {@link Product#configureSpecTemplate} 重建路径
     * 调用——模板变更后存活 SKU 的摘要刷新为当前模板派生值；身份
     * （ID/归属商品）与价格/启用状态不变）。
     *
     * @param specHash    新规范化摘要
     * @param specSummary 新可读摘要
     */
    void refreshSpecDerived(String specHash, String specSummary) {
        this.specHash = specHash;
        this.specSummary = specSummary;
    }
}