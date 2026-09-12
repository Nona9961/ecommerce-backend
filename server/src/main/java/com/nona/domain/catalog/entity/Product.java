package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.IDUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 商品聚合根（catalog 域，草稿形态）：商品主体（SPU 内容：名称/描述/
 * 平台类目引用/品牌引用）+ 图片引用集合（product_image）+ 自定义属性
 * 键值对集合（product_attribute）+ 规格模板（值对象，整体替换）+ 可售
 * 单元集合（product_sku，实体，由规格模板笛卡尔积生成）。
 * <p>
 * 关键不变量（全部收敛在本聚合方法内，包外无直接字段变更路径）：
 * <ol>
 *     <li>商品名称必填非空（草稿必填项，无名草稿拒绝保存）；</li>
 *     <li>同一商品至多一条主图（设置新主图自动清除原主图标记；删除主图后
 *         主图位清空）；</li>
 *     <li>图片 URL 必填非空（引用管理只存 URL 字符串，文件存储走 /files）；</li>
 *     <li>属性键必填非空；同一商品内属性键唯一（重复键无业务意义）；</li>
 *     <li>子实体 ID 在聚合内唯一（防御性，ID 由 Snowflake 生成）；</li>
 *     <li>同商品内规格组合唯一（specHash 唯一，DB 唯一约束 + 聚合守卫双
 *         保险）；SKU 只能由模板配置生成/回收，无独立创建路径；价格可空
 *         （未定价合法，提交上架时必填校验拒绝）非空时为正；</li>
 *     <li>创建即 DRAFT（草稿可保存不生效）；</li>
 *     <li>提交上架要求（规格模板非空且 ≥1 启用 SKU、全部 SKU 已定价正
 *         整数分、主图/平台类目/品牌齐备）与在售状态不变量（同上齐备要求
 *         在主图删除路径的持续守卫）；</li>
 *     <li>状态机合法迁移收敛为聚合方法（submit/approve/reject/敏感编辑分
 *         流；非法迁移拒绝；已下架态无合法迁移，下架端点属后续阶段）；</li>
 *     <li>待审核期内容冻结（编辑写面拒绝——提交冻结内容，驳回后回草稿
 *         可修改重提）。</li>
 * </ol>
 * 引用语义：平台类目/品牌可空（草稿允许不完整）；引用目标的存在性与启用
 * 状态校验在用例层（引用查询能力由平台分类/品牌仓储提供），聚合不感知外部资源。
 *
 * @author nona9961
 */
public class Product {

    /**
     * 单商品 SKU 组合数上限（防组合爆炸的防御性不变量，模板配置路径守卫：
     * 组合数超限拒绝配置）。依据：实物电商单 SPU 可售变体行业常见上限为
     * 200 量级，当前商品体量足够覆盖多规格场景，同时约束接口响应与
     * 前端渲染规模。
     */
    public static final int MAX_SKU_COMBINATIONS = 200;

    /**
     * 商品 ID（Snowflake，聚合根标识）
     */
    private final Long id;

    /**
     * 归属店铺 ID（tenant=shopId，创建时定型不可变）
     */
    private final Long shopId;

    /**
     * 商品名称
     */
    private String name;

    /**
     * 商品描述（可空）
     */
    private String description;

    /**
     * 平台类目 ID（可空引用）
     */
    private Long categoryId;

    /**
     * 品牌 ID（可空引用）
     */
    private Long brandId;

    /**
     * 商品状态（状态机值定型：DRAFT / PENDING_REVIEW / ON_SALE /
     * DELISTED；迁移守卫收敛在聚合方法，非法迁移拒绝；下架端点属后续阶段）
     */
    private ProductStatus status;

    /**
     * 规格模板（整体替换语义；null=尚未配置模板，与空模板（0 维度）区分）
     */
    private SpecTemplate specTemplate;

    /**
     * 可售单元集合（保持模板展开序）
     */
    private final List<Sku> skus = new ArrayList<>();

    /**
     * 图片引用集合（保持加入序）
     */
    private final List<ProductImage> images = new ArrayList<>();

    /**
     * 自定义属性集合（保持加入序）
     */
    private final List<ProductAttribute> attributes = new ArrayList<>();

    /**
     * 待审草稿内容（字段级分流承载：敏感字段编辑后的新内容暂存位，不落
     * 主表生效位——待审核内容不是生效内容；审核通过后覆盖正式内容，驳回
     * 后作废；null=无待审草稿）
     */
    private ProductContent pendingContent;

    /**
     * 运费模板 ID（可空引用，主表列承载）：商品级绑定店铺运费模板
     * （商品绑店铺运费模板；同店铺 freight_template 聚合根引用，绑定目标
     * 存在性/归属校验在用例层）。可空=未绑定（详情运费区按无模板呈现，
     * 下单运费语义由订单域按模板缺失自行裁定）。
     */
    private Long freightTemplateId;

    /**
     * 店铺分类绑定集合（从表 product_shop_category_rel 行，元素=绑定
     * 值对象）：商品可属多个店铺分类（商品与店铺分类多对多，商品侧持有分类 id
     * 集合——店铺分类实体生命周期归 Shop 聚合，本聚合只持引用）。
     */
    private final List<ProductShopCategoryRef> shopCategoryRefs = new ArrayList<>();

    /**
     * 构造商品（仅 Factory 与仓储加载重建调用）：名称必填校验，
     * 其余主体字段可空（草稿允许不完整）。
     *
     * @param id          商品 ID
     * @param shopId      归属店铺 ID
     * @param name        商品名称（必填非空）
     * @param description 商品描述（可空）
     * @param categoryId  平台类目 ID（可空）
     * @param brandId     品牌 ID（可空）
     * @param status      商品状态
     */
    public Product(Long id, Long shopId, String name, String description,
                   Long categoryId, Long brandId, ProductStatus status) {
        this(id, shopId, name, description, categoryId, brandId, status, null, List.of());
    }

    /**
     * 构造商品（仅 Factory 与仓储加载重建调用）：名称必填校验，
     * 其余主体字段可空（草稿允许不完整）；规格模板可空（尚未配置）；
     * SKU 集直接装入（持久化加载路径——聚合内不变量由重建路径维护，
     * 装载路径信任持久化数据）。
     *
     * @param id           商品 ID
     * @param shopId       归属店铺 ID
     * @param name         商品名称（必填非空）
     * @param description  商品描述（可空）
     * @param categoryId   平台类目 ID（可空）
     * @param brandId      品牌 ID（可空）
     * @param status       商品状态
     * @param specTemplate 规格模板（可空=尚未配置模板）
     * @param skus         SKU 集合（可空=空集合）
     */
    public Product(Long id, Long shopId, String name, String description,
                   Long categoryId, Long brandId, ProductStatus status,
                   SpecTemplate specTemplate, List<Sku> skus) {
        this.id = id;
        this.shopId = shopId;
        this.name = requireName(name);
        this.description = description;
        this.categoryId = categoryId;
        this.brandId = brandId;
        this.status = status;
        this.specTemplate = specTemplate;
        if (skus != null) {
            this.skus.addAll(skus);
        }
    }

    /**
     * 商品 ID。
     *
     * @return 商品 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 归属店铺 ID。
     *
     * @return 店铺 ID
     */
    public Long getShopId() {
        return shopId;
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
     * 商品描述。
     *
     * @return 描述；未填写为 null
     */
    public String getDescription() {
        return description;
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
     * 品牌 ID。
     *
     * @return 品牌 ID；未挂载为 null
     */
    public Long getBrandId() {
        return brandId;
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
     * 图片引用集合快照（保持加入序，不可变副本）。
     *
     * @return 图片引用列表
     */
    public List<ProductImage> imagesOrdered() {
        return List.copyOf(images);
    }

    /**
     * 自定义属性集合快照（保持加入序，不可变副本）。
     *
     * @return 属性列表
     */
    public List<ProductAttribute> attributesOrdered() {
        return List.copyOf(attributes);
    }

    /**
     * 当前主图。
     *
     * @return 主图引用；未设置主图返回空
     */
    public Optional<ProductImage> primaryImage() {
        return images.stream().filter(ProductImage::isPrimary).findFirst();
    }

    /**
     * 更新商品主体：名称必填非空（不变量 1）；描述/类目/品牌可空
     * （引用目标存在性与启用状态校验在用例层）。
     * <p>
     * 写面冻结守卫：待审核期（内容冻结，驳回后回草稿可改）与已下架态
     * （无编辑路径）拒绝编辑——草稿直改与在售编辑（分流）走本方法。
     *
     * @param name        新名称
     * @param description 新描述（可空）
     * @param categoryId  新平台类目 ID（可空）
     * @param brandId     新品牌 ID（可空）
     */
    public void updateInfo(String name, String description, Long categoryId, Long brandId) {
        requireEditable();
        this.name = requireName(name);
        this.description = description;
        this.categoryId = categoryId;
        this.brandId = brandId;
    }

    /**
     * 整体内容重置（回滚路径唯一内容入口）：以历史版本快照重建的内容载体
     * 替换聚合当前全部内容——主体（名称/描述/类目/品牌引用）+ 图片引用
     * 集合 + 自定义属性集合 + 规格模板 + SKU 集合整体重置，等价于把聚合
     * 恢复到保存该快照时刻的内容形态（SKU 实体身份/价格/启用状态一并
     * 恢复，SKU 集不参与组合匹配存活语义——回滚是整版替换而非增量调节）。
     * <p>
     * 守卫（不变量收敛点）：
     * <ol>
     *     <li>名称必填非空（不变量 1）；</li>
     *     <li>图片 URL 必填 / 主图至多一条 / ID 唯一（不变量 2/3/5，逐条
     *         经既有新增守卫路径装载）；</li>
     *     <li>属性键唯一与键必填（不变量 4，经既有新增守卫路径装载）；</li>
     *     <li>规格模板结构合法性由值对象构造路径保证，组合数不超上限
     *         （{@link #MAX_SKU_COMBINATIONS}）；模板 null = 清空模板与
     *         SKU 集（快照内未配置模板的合法历史形态）；</li>
     *     <li>SKU 集整体装入（装载语义，信任历史快照——快照保存时已通过
     *         全部校验；装入校验为防御性兜底，默认由身份/组合摘要字段的
     *         不可空性保证）。</li>
     * </ol>
     * 触发语义：内容重置（生成新版本行）与保存编排（用例层事务）同属
     * 回滚流程——本方法只负责聚合内容形态，版本链留痕由用例层承载。
     * 子实体归属重建：快照不承载卡片元素归属（版本行按商品维度定位，
     * 归属恒等于所属商品），装载时以当前商品 ID 重建图片/属性/SKU 的
     * 归属——子实体归属必为本聚合（聚合内身份一致性不变量）。
     *
     * @param content 内容载体（由历史版本快照重建；必填非空）
     */
    public void restoreContent(ProductContent content) {
        if (content == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code(), "内容载体不能为空");
        }
        this.name = requireName(content.name());
        this.description = content.description();
        this.categoryId = content.categoryId();
        this.brandId = content.brandId();
        if (content.specTemplate() == null) {
            this.specTemplate = null;
            skus.clear();
        } else {
            if (content.specTemplate().combinationCount() > MAX_SKU_COMBINATIONS) {
                throw new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_SKU_COUNT_EXCEEDED.code(), "SKU数量超上限");
            }
            this.specTemplate = content.specTemplate();
            skus.clear();
            for (final Sku sku : content.skus()) {
                skus.add(new Sku(sku.getId(), id, sku.getSpecHash(), sku.getSpecSummary(),
                        sku.getPrice(), sku.isEnabled()));
            }
        }
        images.clear();
        for (final ProductImage image : content.images()) {
            appendImage(new ProductImage(image.getId(), id, image.getUrl(), image.isPrimary()));
        }
        attributes.clear();
        for (final ProductAttribute attribute : content.attributes()) {
            appendAttribute(new ProductAttribute(attribute.getId(), id, attribute.getKey(), attribute.getValue()));
        }
    }

    /**
     * 提交上架：草稿 → 待审核（状态机合法迁移之一；驳回后回到草稿可修改
     * 后再次提交）。
     * <p>
     * 守卫（不变量收敛点）：
     * <ol>
     *     <li>状态守卫：仅草稿态可提交（在售/待审/已下架态提交为非法迁移，
     *         拒绝）；待审草稿位必须为空（草稿态下待审位恒空——驳回已作废、
     *         分流仅在在售态触发，非空即异常形态防御性拒绝）；</li>
     *     <li>完整性守卫（提交上架要求）：规格模板非空且 ≥1 启用 SKU；
     *         全部 SKU 已定价且为正整数分；主图存在；平台类目与品牌已挂载
     *         ——任一不满足拒绝（逐项细化业务码，商家据此修正）。</li>
     * </ol>
     * 触发语义：提交无内容变化（待审内容 = 草稿当前内容），不产生编辑
     * 版本行；状态迁移落库与审核结论版本行由用例层编排。
     */
    public void submitForReview() {
        if (status != ProductStatus.DRAFT || pendingContent != null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_STATUS_ILLEGAL.code(), "仅草稿商品可提交上架");
        }
        requireCompleteness(specTemplate, skus, images, categoryId, brandId);
        status = ProductStatus.PENDING_REVIEW;
    }

    /**
     * 审核通过：待审核 → 在售（平台裁定，买家开始可见生效内容）。
     * <p>
     * 守卫（不变量收敛点）：
     * <ol>
     *     <li>状态守卫：仅待审核态可审批（草稿未提交直接审批、在售重复
     *         审批、已下架态审批均为非法迁移，拒绝）；</li>
     *     <li>生效内容守卫：存在待审草稿（敏感字段编辑分流）时，待审内容
     *         通过完整性校验后覆盖正式内容（买家可见旧内容 → 新内容）；
     *         无待审草稿（直提场景）时正式内容通过完整性校验——通过后
     *         生效内容必须满足在售要求（主图/类目/品牌齐备、模板与启用
     *         SKU、全定价）；</li>
     *     <li>待审草稿随通过清空（待审位状态机语义：裁定后作废/移交）。</li>
     * </ol>
     * 触发语义：内容变化（待审覆盖）由本方法承载；审核结论版本行
     * （REVIEW_PASS，含生效内容快照）由用例层编排。
     */
    public void approve() {
        if (status != ProductStatus.PENDING_REVIEW) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_STATUS_ILLEGAL.code(), "仅待审核商品可审核通过");
        }
        if (pendingContent != null) {
            requireCompleteness(pendingContent.specTemplate(), pendingContent.skus(),
                    pendingContent.images(), pendingContent.categoryId(), pendingContent.brandId());
            restoreContent(pendingContent);
            pendingContent = null;
        } else {
            requireCompleteness(specTemplate, skus, images, categoryId, brandId);
        }
        status = ProductStatus.ON_SALE;
    }

    /**
     * 审核驳回：待审核 → 草稿（可修改重提；驳回原因随审核结论版本行承载）。
     * <p>
     * 守卫（不变量收敛点）：
     * <ol>
     *     <li>状态守卫：仅待审核态可驳回（其余状态驳回为非法迁移，拒绝）；</li>
     *     <li>原因守卫：驳回原因必填非空（商家据此修改重提，无原因驳回无
     *         业务意义）；</li>
     *     <li>待审草稿随驳回作废（待审内容不生效也不保留——驳回后修改
     *         走草稿直改再重提）。</li>
     * </ol>
     * 触发语义：驳回不改变生效内容（买家继续可见旧内容）；审核结论版本
     * 行（REJECT，承载原因与驳回时刻生效内容快照）由用例层编排。
     *
     * @param reason 驳回原因（必填非空）
     */
    public void reject(String reason) {
        if (status != ProductStatus.PENDING_REVIEW) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_STATUS_ILLEGAL.code(), "仅待审核商品可驳回");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_REJECT_REASON_BLANK.code(), "驳回原因不能为空");
        }
        pendingContent = null;
        status = ProductStatus.DRAFT;
    }

    /**
     * 敏感字段编辑分流：在售 → 待审核（字段级审核白名单路由——编辑命中
     * 交易敏感字段时，编辑内容入待审草稿位，本聚合内容回退为调用方提供的
     * 旧生效内容，买家继续可见旧版直至平台裁定）。
     * <p>
     * 守卫（不变量收敛点）：
     * <ol>
     *     <li>状态守卫：仅在售态分流（草稿/待审/已下架态编辑不适用：待审
     *         期内容冻结、草稿与已下架直改走保存路径）；</li>
     *     <li>待审位守卫：分流前待审位必须为空（至多一个待审版本，重复
     *         分流为异常形态拒绝）；</li>
     *     <li>完整性守卫：待审内容（= 本聚合编辑后的当前内容）必须满足
     *         提交上架要求（规格模板/启用 SKU/全定价/主图/类目/品牌）——
     *         待审内容不完整则待审无意义，直接拒绝；</li>
     *     <li>回退守卫：旧生效内容装载沿用 {@link #restoreContent} 路径
     *         （名称必填等聚合内既有守卫）。</li>
     * </ol>
     * 触发语义：分流不产生编辑版本行（待审核内容不是生效内容，版本链只
     * 留痕生效内容）；状态迁移与待审草稿位落库由用例层编排，审核通过后
     * 待审内容经 {@link #approve} 覆盖正式内容并插审核结论版本行。
     *
     * @param effectiveContent 旧生效内容载体（调用方在编辑前留存的快照；
     *                         必填非空）
     */
    public void stageSensitiveEdit(ProductContent effectiveContent) {
        if (status != ProductStatus.ON_SALE) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_STATUS_ILLEGAL.code(), "仅在售商品可敏感编辑分流");
        }
        if (pendingContent != null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_STATUS_ILLEGAL.code(), "已存在待审草稿，重复分流拒绝");
        }
        if (effectiveContent == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code(), "生效内容载体不能为空");
        }
        requireCompleteness(specTemplate, skus, images, categoryId, brandId);
        final ProductContent edited = new ProductContent(name, description, categoryId, brandId,
                List.copyOf(images), List.copyOf(attributes), specTemplate, List.copyOf(skus));
        restoreContent(effectiveContent);
        pendingContent = edited;
        status = ProductStatus.PENDING_REVIEW;
    }

    /**
     * 装载待审草稿内容（持久化加载装配路径专用：由仓储从主表
     * pending_draft 列重建——装载路径信任持久化数据，不重复业务校验，
     * 与 SKU 集装载语义一致；编辑/审批业务路径不得调用）。
     *
     * @param pendingContent 待审草稿内容（持久化读回；null=清空待审位）
     */
    public void restorePendingContent(ProductContent pendingContent) {
        this.pendingContent = pendingContent;
    }

    /**
     * 是否存在待审草稿内容（敏感字段编辑后待平台裁定，买家不可见）。
     *
     * @return 有待审草稿返回 true
     */
    public boolean hasPendingContent() {
        return pendingContent != null;
    }

    /**
     * 手动下架：在售 → 已下架（手动下架生效后买家不可见）。
     * <p>
     * 守卫（不变量收敛点）：仅 ON_SALE 可下架——草稿/待审/已下架态下架
     * 为非法迁移拒绝（{@code CATALOG_PRODUCT_STATUS_ILLEGAL}）；驳回/待审
     * 商品无下架语义（未生效内容无需下架）。
     * <p>
     * 触发语义：下架为生命周期状态迁移（非内容变更），不产生编辑版本行；
     * 状态落库由用例层编排。
     */
    public void delist() {
        if (status != ProductStatus.ON_SALE) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_STATUS_ILLEGAL.code(), "仅在售商品可手动下架");
        }
        status = ProductStatus.DELISTED;
    }

    /**
     * 手动重新上架：已下架 → 在售（状态机「在售 ⇄ 已下架」回迁语义——
     * 下架期间内容冻结不可编辑，重新上架内容 = 曾审核通过的生效内容，
     * 免重审直回在售）。
     * <p>
     * 守卫（不变量收敛点）：
     * <ol>
     *     <li>仅 DELISTED 可重新上架（其余状态上架为非法迁移，拒绝）；</li>
     *     <li>完整性防御校验：上架前生效内容必须仍满足在售要求（模板/启用
     *         SKU/全定价/主图/类目/品牌——内容在下架期冻结，防御脏数据
     *         形态兜底）。</li>
     * </ol>
     * 触发语义：重新上架无内容变化，不产生编辑版本行；状态落库由用例层编排。
     */
    public void relist() {
        if (status != ProductStatus.DELISTED) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_STATUS_ILLEGAL.code(), "仅已下架商品可重新上架");
        }
        requireRelistCompleteness();
        status = ProductStatus.ON_SALE;
    }

    /**
     * 重新上架完整性防御校验（下架期内容冻结，防御脏数据形态兜底）：
     * 上架前生效内容必须仍满足在售要求——主图/平台类目/品牌齐备（引用
     * 与展示要素，先校验）→ 规格模板非空且含启用 SKU、全部 SKU 已定价
     * 且为正整数分。逐项细化业务码（商家据此修正）；校验顺序为在售
     * 展示要素在前（下架商品的最常见失效形态为主图/引用缺失）。
     */
    private void requireRelistCompleteness() {
        if (images.stream().noneMatch(ProductImage::isPrimary)) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_MAIN_IMAGE_REQUIRED.code(), "重新上架要求有主图");
        }
        if (categoryId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_CATEGORY_REQUIRED.code(), "重新上架要求已挂平台类目");
        }
        if (brandId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_BRAND_REQUIRED.code(), "重新上架要求已挂品牌");
        }
        if (specTemplate == null || specTemplate.combinationCount() == 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_REQUIRED.code(), "重新上架要求规格模板非空");
        }
        if (skus.isEmpty() || skus.stream().noneMatch(Sku::isEnabled)) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SKU_ENABLED_REQUIRED.code(), "重新上架要求至少一个启用SKU");
        }
        if (skus.stream().anyMatch(sku -> sku.getPrice() == null || sku.getPrice() <= 0)) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SKU_PRICE_UNSET.code(), "重新上架要求全部SKU已定价");
        }
    }

    /**
     * 整体替换店铺分类绑定集（商品侧绑定/解绑的表单语义——店铺分类
     * 编辑页多选勾选，保存即整体替换；分类集合可为空=清空全部绑定）。
     * <p>
     * 守卫（不变量收敛点）：
     * <ol>
     *     <li>写面冻结守卫：待审核期（内容冻结）与已下架态（无编辑路径）
     *         拒绝绑定（与既有编辑路径同语义）；</li>
     *     <li>元素守卫：分类 ID 必填非空、集合内去重（同一分类重复绑定无
     *         业务意义，绑定行 (product_id, shop_category_id) 唯一约束
     *         双保险）；</li>
     *     <li>绑定目标归属校验在用例层（分类必须属于本商品所属店铺——
     *         Shop 聚合加载校验，本聚合不感知外部资源）。</li>
     * </ol>
     * 替换语义：旧绑定行整体移除、新绑定行重建（绑定低频，不做行级
     * diff 最小化；从表行变更仍由仓储变更集驱动落库）。分类变更属展示类
     * 编辑（敏感字段集不含店铺分类）——在售态直改免审。
     *
     * @param shopCategoryIds 目标店铺分类 ID 集合（null 按空集=清空）
     */
    public void replaceShopCategories(java.util.Collection<Long> shopCategoryIds) {
        requireEditable();
        final java.util.LinkedHashSet<Long> targets = new java.util.LinkedHashSet<>();
        final java.util.Collection<Long> requested = shopCategoryIds == null ? java.util.List.of() : shopCategoryIds;
        for (final Long categoryId : requested) {
            if (categoryId == null) {
                throw new BusinessException(
                        com.nona.exceptions.BusinessCode.VALIDATION_FAILED.code(), "店铺分类不能为空");
            }
            targets.add(categoryId);
        }
        shopCategoryRefs.clear();
        for (final Long categoryId : targets) {
            shopCategoryRefs.add(new ProductShopCategoryRef(IDUtils.generateID(), id, categoryId));
        }
    }

    /**
     * 装载店铺分类绑定行（持久化加载装配路径专用：由仓储从从表行重建
     * 绑定集合——装载路径信任持久化数据，且不受写面冻结守卫约束；编辑
     * 业务路径不得调用）。
     *
     * @param ref 绑定行（持久化读回，其 ID 为行主键）
     */
    public void restoreShopCategoryRef(ProductShopCategoryRef ref) {
        appendShopCategoryRef(ref);
    }

    /**
     * 装载店铺分类绑定行（聚合内从表行装载路径共用）：id 在聚合内唯一
     * （不变量 5）、归属商品必为本聚合——装载路径信任持久化数据，校验
     * 为防御性兜底；不受写面冻结守卫约束（待审核/在售商品装配需要装载
     * 从表行）。
     *
     * @param ref 绑定行（持久化读回，其 ID 为行主键）
     */
    private void appendShopCategoryRef(ProductShopCategoryRef ref) {
        if (ref == null) {
            throw new BusinessException(
                    com.nona.exceptions.BusinessCode.VALIDATION_FAILED.code(), "绑定行不能为空");
        }
        if (getShopCategoryRefById(ref.id()).isPresent()) {
            throw new BusinessException(
                    com.nona.exceptions.BusinessCode.VALIDATION_FAILED.code(), "绑定行已存在");
        }
        if (!ref.productId().equals(id)) {
            throw new BusinessException(
                    com.nona.exceptions.BusinessCode.VALIDATION_FAILED.code(), "绑定行归属不符");
        }
        shopCategoryRefs.add(ref);
    }

    /**
     * 按 ID 取店铺分类绑定行（变更集分发用：从表行增删按行主键定位）。
     *
     * @param refId 绑定行 ID
     * @return 绑定行；不存在返回空
     */
    public Optional<ProductShopCategoryRef> getShopCategoryRefById(Long refId) {
        return shopCategoryRefs.stream().filter(ref -> ref.id().equals(refId)).findFirst();
    }

    /**
     * 店铺分类绑定集合快照（保持绑定序，不可变副本）。
     *
     * @return 绑定行列表
     */
    public List<ProductShopCategoryRef> shopCategoryRefsOrdered() {
        return List.copyOf(shopCategoryRefs);
    }

    /**
     * 已绑定的店铺分类 ID 列表（保持绑定序，不可变副本；回显与差集计算
     * 用）。
     *
     * @return 分类 ID 列表；无绑定为空列表
     */
    public List<Long> shopCategoryIdsOrdered() {
        return shopCategoryRefs.stream().map(ProductShopCategoryRef::shopCategoryId).toList();
    }

    /**
     * 绑定/解绑运费模板（商品绑店铺运费模板；null=解绑——运费模板为可空
     * 引用，未绑定商品详情运费区按无模板呈现）。
     * <p>
     * 守卫（不变量收敛点）：
     * <ol>
     *     <li>写面冻结守卫：待审核期（内容冻结）与已下架态（无编辑路径）
     *         拒绝绑定（与既有编辑路径同语义）；</li>
     *     <li>绑定目标校验在用例层（模板必须存在且属于本商品所属店铺——
     *         FreightTemplate 聚合加载校验；停用模板允许绑定——展示历史
     *         归属合法，新订单计费守卫由运费计算器承载）。</li>
     * </ol>
     * 模板绑定属展示/运营配置（敏感字段集不含运费模板引用）——
     * 在售态直改免审。
     *
     * @param freightTemplateId 目标模板 ID（null=解绑）
     */
    public void bindFreightTemplate(Long freightTemplateId) {
        requireEditable();
        this.freightTemplateId = freightTemplateId;
    }

    /**
     * 装载运费模板引用（持久化加载装配路径专用：由仓储从主表列重建
     * 引用——装载路径信任持久化数据，不受写面冻结守卫约束；编辑/审批
     * 业务路径不得调用）。
     *
     * @param freightTemplateId 模板 ID（持久化读回；null=未绑定）
     */
    public void restoreFreightTemplateId(Long freightTemplateId) {
        this.freightTemplateId = freightTemplateId;
    }

    /**
     * 已绑定的运费模板 ID。
     *
     * @return 模板 ID；未绑定返回 null
     */
    public Long getFreightTemplateId() {
        return freightTemplateId;
    }

    /**
     * 待审草稿内容（不可变载体；无待审草稿返回空）。
     *
     * @return 待审草稿内容；无返回空
     */
    public Optional<ProductContent> getPendingContent() {
        return Optional.ofNullable(pendingContent);
    }

    /**
     * 编辑分流判定（字段级审核白名单路由，catalog 域常量配置）：按变更
     * 路径集合 + 增删集合名集合投影判定本次保存的编辑方向。
     * <p>
     * 交易敏感字段集（命中任一即转待审核）：标题（路径 name）、平台
     * 类目（categoryId）、品牌（brandId）、SKU 价格（skus[&lt;id&gt;].price）、
     * SKU 规格构成（skus[&lt;id&gt;].specHash / skus[&lt;id&gt;].specSummary /
     * SKU 集合增删）；展示类字段（描述 description、详情图 images[*]、
     * 自定义属性 attributes[*]）；其余路径（如 SKU 启停 skus[&lt;id&gt;].enabled）
     * 不在任一集合内按展示类处理（直改免审）。判定为纯函数（输入投影
     * 与输出路由），供仓储层变更集投影调用。
     *
     * @param changedPaths       变更路径集合（变更集叶子节点 path，如
     *                           {@code skus[123].price}）；null 按空集处理
     * @param mutatedCollections 集合增删节点的集合字段名集合（如
     *                           {@code skus}）；null 按空集处理
     * @return 编辑方向判定结果（{@link EditSensitivity}）
     */
    public static EditSensitivity classifyEditSensitivity(java.util.Set<String> changedPaths,
                                                           java.util.Set<String> mutatedCollections) {
        final java.util.Set<String> paths = changedPaths == null ? java.util.Set.of() : changedPaths;
        final java.util.Set<String> collections = mutatedCollections == null ? java.util.Set.of() : mutatedCollections;
        if (paths.isEmpty() && collections.isEmpty()) {
            return EditSensitivity.NONE;
        }
        if (collections.contains(SKUS_COLLECTION)) {
            return EditSensitivity.SENSITIVE;
        }
        for (final String path : paths) {
            if (isSensitivePath(path)) {
                return EditSensitivity.SENSITIVE;
            }
        }
        return EditSensitivity.DISPLAY_ONLY;
    }

    /**
     * 敏感路径判定：变更路径归一化（集合元素 ID 掩除）后命中交易敏感
     * 字段集即敏感——根字段为完整路径（如 {@code name}），集合叶子为
     * 掩除后的形态（如 {@code skus[].price}）。
     *
     * @param path 变更叶子路径（null 拒绝）
     * @return 命中敏感字段返回 true
     */
    private static boolean isSensitivePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return SENSITIVE_LEAF_PATHS.contains(path.replaceAll(ELEMENT_ID_PATTERN, "[]"));
    }

    /**
     * 新增图片引用（不变量 2/3/5）：URL 必填非空；ID 在聚合内唯一；
     * 请求设为主图时自动清除原主图标记（至多一条主图）。写面冻结守卫
     * （待审核期/已下架态拒绝）；在售态新增图片为展示类变更（直改免审）。
     *
     * @param image 图片引用（工厂创建，待入聚合）
     */
    public void addImage(ProductImage image) {
        requireEditable();
        appendImage(image);
    }

    /**
     * 新增图片引用（内容装载内部路径，无写面冻结守卫）：校验 URL 必填
     * 非空、ID 唯一、主图唯一后入集合——持久化装载与整体内容重置
     * （{@link #restoreContent} 的图片重建）不走公开写面（待审核期审批
     * 覆盖、敏感编辑回退等状态机迁移路径需在受限状态下装载）。
     *
     * @param image 图片引用（待入聚合）
     */
    private void appendImage(ProductImage image) {
        if (image == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_IMAGE_URL_BLANK.code(), "图片不能为空");
        }
        if (image.getUrl() == null || image.getUrl().isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_IMAGE_URL_BLANK.code(), "图片URL不能为空");
        }
        if (getImageById(image.getId()).isPresent()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_IMAGE_DUPLICATE.code(), "图片已存在");
        }
        if (image.isPrimary()) {
            images.forEach(existing -> existing.markPrimary(false));
        }
        images.add(image);
    }

    /**
     * 装载图片引用（持久化加载装配路径专用）：由仓储从主表从表行重建
     * 图片集合——装载路径信任持久化数据（校验为聚合内不变量防御性兜底），
     * 且不受写面冻结守卫约束（待审核/在售商品装配需要装载从表行）；
     * 编辑/审核业务路径不得调用。
     *
     * @param image 图片引用（持久化读回，其 ID 为行主键）
     */
    public void restoreImage(ProductImage image) {
        appendImage(image);
    }

    /**
     * 装载自定义属性（持久化加载装配路径专用）：与 {@link #restoreImage}
     * 同语义——装载信任持久化数据、不受写面冻结守卫约束；编辑/审核业务
     * 路径不得调用。
     *
     * @param attribute 属性键值对（持久化读回，其 ID 为行主键）
     */
    public void restoreAttribute(ProductAttribute attribute) {
        appendAttribute(attribute);
    }

    /**
     * 删除图片引用（不变量 2）：目标必须存在；主图被删后主图位清空，
     * 其余图片标记不变。守卫：待审核期/已下架态拒绝编辑；在售商品删除
     * 主图拒绝（在售不变量：在售商品必须有主图——删主图即删后在售形态
     * 不完整）。
     *
     * @param imageId 图片引用 ID
     */
    public void removeImage(Long imageId) {
        requireEditable();
        final ProductImage image = requireExistingImage(imageId);
        if (status == ProductStatus.ON_SALE && image.isPrimary()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_MAIN_IMAGE_REQUIRED.code(), "在售商品必须保留主图");
        }
        images.remove(image);
    }

    /**
     * 设置主图（不变量 2）：目标必须存在；清除原主图标记后设目标为主图。
     * 写面冻结守卫（待审核期/已下架态拒绝）；在售态换主图为展示类变更。
     *
     * @param imageId 图片引用 ID
     */
    public void setPrimaryImage(Long imageId) {
        requireEditable();
        final ProductImage image = requireExistingImage(imageId);
        images.forEach(existing -> existing.markPrimary(false));
        image.markPrimary(true);
    }

    /**
     * 新增自定义属性（不变量 4/5）：键必填非空；键在聚合内唯一；ID 唯一。
     * 写面冻结守卫（待审核期/已下架态拒绝）；在售态新增属性为展示类变更。
     *
     * @param attribute 属性键值对（工厂创建，待入聚合）
     */
    public void addAttribute(ProductAttribute attribute) {
        requireEditable();
        appendAttribute(attribute);
    }

    /**
     * 新增自定义属性（内容装载内部路径，无写面冻结守卫）：校验键必填
     * 非空、键与 ID 唯一后入集合——持久化装载与整体内容重置
     * （{@link #restoreContent} 的属性重建）不走公开写面。
     *
     * @param attribute 属性键值对（待入聚合）
     */
    private void appendAttribute(ProductAttribute attribute) {
        if (attribute == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_KEY_BLANK.code(), "属性不能为空");
        }
        if (attribute.getKey() == null || attribute.getKey().isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_KEY_BLANK.code(), "属性键不能为空");
        }
        if (getAttributeById(attribute.getId()).isPresent()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_DUPLICATE.code(), "属性已存在");
        }
        requireKeyFree(attribute.getKey(), null);
        attributes.add(attribute);
    }

    /**
     * 更新自定义属性（不变量 4）：目标必须存在；新键非空且与其余属性键保持
     * 唯一（排除自身，改回自身键合法）；值可空。写面冻结守卫（待审核期/
     * 已下架态拒绝）；在售态改属性为展示类变更（直改免审）。
     *
     * @param attributeId 属性 ID
     * @param key         新键
     * @param value       新值（可空）
     */
    public void updateAttribute(Long attributeId, String key, String value) {
        requireEditable();
        final ProductAttribute attribute = requireExistingAttribute(attributeId);
        if (key == null || key.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_KEY_BLANK.code(), "属性键不能为空");
        }
        requireKeyFree(key, attributeId);
        attribute.update(key, value);
    }

    /**
     * 删除自定义属性：目标必须存在。写面冻结守卫（待审核期/已下架态
     * 拒绝）；在售态删属性为展示类变更。
     *
     * @param attributeId 属性 ID
     */
    public void removeAttribute(Long attributeId) {
        requireEditable();
        final ProductAttribute attribute = requireExistingAttribute(attributeId);
        attributes.remove(attribute);
    }

    /**
     * 规格模板（null=尚未配置模板；空模板（0 维度）为非空对象）。
     *
     * @return 规格模板；未配置返回空
     */
    public Optional<SpecTemplate> getSpecTemplate() {
        return Optional.ofNullable(specTemplate);
    }

    /**
     * 整体替换规格模板并重建 SKU 集（模板配置主入口）：按模板笛卡尔积
     * 展开组合，匹配组合（见下方匹配语义）保留既有 SKU 实体——价格与
     * 启用状态存活、SKU ID 不变（跨域引用稳定），规格摘要刷新为当前模板
     * 派生值；新增组合生成新 SKU（默认未定价、停用）；消失组合的 SKU
     * 移除；空模板清空 SKU 集。
     * <p>
     * 守卫（不变量收敛点）：
     * <ol>
     *     <li>写面冻结守卫：待审核期（内容冻结，驳回后可改）与已下架态
     *         （无编辑路径）拒绝配置；</li>
     *     <li>模板必填非空（null 拒绝）；</li>
     *     <li>组合数不超上限（{@link #MAX_SKU_COMBINATIONS}，防组合爆炸）；</li>
     *     <li>模板结构合法性（维度名/值约束）由值对象构造路径保证。</li>
     * </ol>
     * 组合匹配语义：specHash 为规范化摘要（与维度配置顺序无关），
     * 模板调整维度顺序不改变组合身份；同一模板重复配置因全部组合匹配
     * 而天然幂等（无新增/移除，SKU 集保持原样）。匹配规则为组合归属
     * 判定：新模板组合在旧模板组合中按展开序取第一个「包含关系」命中
     * （新组合的取值对全部存在于旧组合，同组合为包含关系的特例）——
     * 模板收缩维度时存活 SKU 随新组合刷新派生值，其余旧 SKU 移除。
     *
     * @param template 新规格模板（整体替换；空模板=清空 SKU 集）
     */
    public void configureSpecTemplate(SpecTemplate template) {
        requireEditable();
        if (template == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_INVALID.code(), "规格模板不能为空");
        }
        if (template.combinationCount() > MAX_SKU_COMBINATIONS) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SKU_COUNT_EXCEEDED.code(), "SKU数量超上限");
        }
        final List<SpecCombination> oldCombinations =
                specTemplate == null ? List.of() : specTemplate.combinations();
        final boolean[] used = new boolean[skus.size()];
        final List<Sku> rebuilt = new ArrayList<>();
        for (final SpecCombination combination : template.combinations()) {
            Sku survived = matchSurvivor(combination, oldCombinations, used);
            if (survived != null) {
                survived.refreshSpecDerived(combination.hash(), combination.summary());
            } else {
                survived = new Sku(IDUtils.generateID(), id,
                        combination.hash(), combination.summary(), null, false);
            }
            rebuilt.add(survived);
        }
        skus.clear();
        skus.addAll(rebuilt);
        this.specTemplate = template;
    }

    /**
     * SKU 集合快照（保持模板展开序，不可变副本）。
     *
     * @return SKU 列表
     */
    public List<Sku> skusOrdered() {
        return List.copyOf(skus);
    }

    /**
     * 按 ID 取 SKU（不属于本商品呈现为不存在）。
     *
     * @param skuId SKU ID
     * @return SKU；不存在返回空
     */
    public Optional<Sku> getSkuById(Long skuId) {
        return skus.stream().filter(sku -> sku.getId().equals(skuId)).findFirst();
    }

    /**
     * 更新 SKU 价格（价格语义：null=未定价合法——草稿期允许未定价；
     * 非空必须为正整数分，0 与负数无业务意义拒绝）。目标 SKU 必须属于
     * 本商品（不属于按不存在呈现，不泄露归属）。写面冻结守卫（待审核
     * 期/已下架态拒绝）；在售态改价为敏感编辑（转待审核分流）。
     *
     * @param skuId SKU ID
     * @param price 新价格（分；null=清除价格复位未定价）
     */
    public void updateSkuPrice(Long skuId, Long price) {
        requireEditable();
        final Sku sku = requireExistingSku(skuId);
        if (price != null && price <= 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SKU_PRICE_INVALID.code(), "SKU价格必须为正整数");
        }
        sku.updatePrice(price);
    }

    /**
     * 切换 SKU 启用状态（停用 SKU 不出售；启停属运营动作非交易敏感——
     * 在售态启停直改免审，不触发敏感分流）。写面冻结守卫（待审核期/
     * 已下架态拒绝）。
     *
     * @param skuId   SKU ID
     * @param enabled 是否启用
     */
    public void setSkuEnabled(Long skuId, boolean enabled) {
        requireEditable();
        requireExistingSku(skuId).setEnabled(enabled);
    }

    /**
     * 在新模板组合的存活匹配中取第一个未占用的包含关系命中
     * （新组合的取值对全部存在于旧组合，视为同一可售变体的延续——
     * 价格与启用状态、SKU 身份存活）。
     *
     * @param combination      新模板组合
     * @param oldCombinations  旧模板组合列表（展开序，与 skus 一一对应）
     * @param used             旧 SKU 占用标记（每个旧 SKU 至多延续一次）
     * @return 存活 SKU；无匹配返回 null
     */
    private Sku matchSurvivor(SpecCombination combination,
                              List<SpecCombination> oldCombinations, boolean[] used) {
        for (int i = 0; i < oldCombinations.size(); i++) {
            if (!used[i] && isContainedBy(combination, oldCombinations.get(i))) {
                used[i] = true;
                return skus.get(i);
            }
        }
        return null;
    }

    /**
     * 包含关系判定：候选组合的每个取值对都能在承载组合中找到
     * （同组合为自含特例；维度收缩后取子集方向成立）。
     *
     * @param candidate 候选组合（新模板组合）
     * @param carrier   承载组合（旧模板组合）
     * @return 承载组合包含候选组合的全部取值对返回 true
     */
    private static boolean isContainedBy(SpecCombination candidate, SpecCombination carrier) {
        for (final SpecValueRef ref : candidate.valuesOrdered()) {
            if (!ref.getValue().equals(carrier.valueOf(ref.getName()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按 ID 取 SKU 并断言存在（改价/启停的目标必须属于本商品；
     * 不属于本商品即呈现为不存在，不泄露归属信息）。
     *
     * @param skuId SKU ID
     * @return SKU
     */
    private Sku requireExistingSku(Long skuId) {
        return getSkuById(skuId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_SKU_NOT_FOUND.code(), "SKU不存在"));
    }

    /**
     * 按 ID 取图片引用。
     *
     * @param imageId 图片引用 ID
     * @return 图片引用；不存在返回空
     */
    public Optional<ProductImage> getImageById(Long imageId) {
        return images.stream().filter(image -> image.getId().equals(imageId)).findFirst();
    }

    /**
     * 按 ID 取自定义属性。
     *
     * @param attributeId 属性 ID
     * @return 属性；不存在返回空
     */
    public Optional<ProductAttribute> getAttributeById(Long attributeId) {
        return attributes.stream().filter(attribute -> attribute.getId().equals(attributeId)).findFirst();
    }

    /**
     * 按 ID 取图片引用并断言存在（图片编辑/删除的目标必须属于本商品；
     * 不属于本商品即呈现为不存在，不泄露归属信息）。
     *
     * @param imageId 图片引用 ID
     * @return 图片引用
     */
    private ProductImage requireExistingImage(Long imageId) {
        return getImageById(imageId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_IMAGE_NOT_FOUND.code(), "图片不存在"));
    }

    /**
     * 按 ID 取属性并断言存在（属性编辑/删除的目标必须属于本商品；
     * 不属于本商品即呈现为不存在，不泄露归属信息）。
     *
     * @param attributeId 属性 ID
     * @return 属性
     */
    private ProductAttribute requireExistingAttribute(Long attributeId) {
        return getAttributeById(attributeId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_NOT_FOUND.code(), "属性不存在"));
    }

    /**
     * 键唯一性守卫：目标键已被其余属性占用即拒绝（更新场景排除自身）。
     *
     * @param key        目标键
     * @param excludeId 排除的属性 ID（更新场景排除自身；新增传 null）
     */
    private void requireKeyFree(String key, Long excludeId) {
        final boolean occupied = attributes.stream()
                .anyMatch(attribute -> attribute.getKey().equals(key)
                        && (excludeId == null || !attribute.getId().equals(excludeId)));
        if (occupied) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_KEY_DUPLICATE.code(), "属性键已存在");
        }
    }

    /**
     * 名称必填校验（构造与编辑共用）。
     *
     * @param name 商品名称
     * @return 校验通过的名称
     */
    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_NAME_BLANK.code(), "商品名称不能为空");
        }
        return name;
    }

    /**
     * 提交上架完整性校验（提交/审批/敏感编辑分流共用）：规格模板非空且
     * ≥1 启用 SKU、全部 SKU 已定价且为正整数分、主图存在、平台类目与
     * 品牌已挂载——逐项细化业务码（商家据此修正；校验顺序与业务码定义
     * 序一致）。任一不满足拒绝。
     *
     * @param specTemplate 规格模板（null=尚未配置）
     * @param skus         SKU 集合
     * @param images       图片引用集合
     * @param categoryId   平台类目 ID（可空）
     * @param brandId      品牌 ID（可空）
     */
    private static void requireCompleteness(SpecTemplate specTemplate, List<Sku> skus,
                                            List<ProductImage> images, Long categoryId, Long brandId) {
        if (specTemplate == null || specTemplate.combinationCount() == 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_REQUIRED.code(), "提交上架要求规格模板非空");
        }
        if (skus.isEmpty() || skus.stream().noneMatch(Sku::isEnabled)) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SKU_ENABLED_REQUIRED.code(), "提交上架要求至少一个启用SKU");
        }
        if (skus.stream().anyMatch(sku -> sku.getPrice() == null || sku.getPrice() <= 0)) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SKU_PRICE_UNSET.code(), "提交上架要求全部SKU已定价");
        }
        if (images.stream().noneMatch(ProductImage::isPrimary)) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_MAIN_IMAGE_REQUIRED.code(), "提交上架要求有主图");
        }
        if (categoryId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_CATEGORY_REQUIRED.code(), "提交上架要求已挂平台类目");
        }
        if (brandId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_BRAND_REQUIRED.code(), "提交上架要求已挂品牌");
        }
    }

    /**
     * 写面冻结守卫：待审核期编辑拒绝（提交冻结内容，驳回后回草稿可修改
     * 重提——编辑冻结业务码）；已下架态无编辑路径（状态非法业务码——
     * 下架期内容冻结，改内容先重新上架或回草稿生命周期）。在售态编辑
     * 放行（敏感字段编辑转待审核分流、展示字段直改免审均由用例层路由，
     * 本守卫不拦截）。
     */
    private void requireEditable() {
        if (status == ProductStatus.PENDING_REVIEW) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_EDIT_FORBIDDEN.code(), "待审核期内容冻结，驳回后可编辑重提");
        }
        if (status == ProductStatus.DELISTED) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_STATUS_ILLEGAL.code(), "已下架商品不可编辑");
        }
    }

    /**
     * 编辑分流敏感字段集（变更叶子路径形态，集合元素 ID 掩除）：标题、
     * 平台类目、品牌、SKU 价格、SKU 规格构成（组合摘要）。命中任一即
     * 转待审核。
     */
    private static final java.util.Set<String> SENSITIVE_LEAF_PATHS = java.util.Set.of(
            "name", "categoryId", "brandId",
            "skus[].price", "skus[].specHash", "skus[].specSummary");

    /**
     * 集合元素 ID 掩除正则（变更路径归一化：{@code skus[123].price} →
     * {@code skus[].price}，与敏感字段集比较不受具体 ID 影响）。
     */
    private static final String ELEMENT_ID_PATTERN = "\\[[0-9]+\\]";

    /**
     * SKU 集合字段名（集合增删节点的敏感判定目标：SKU 集合增删 = 规格
     * 构成变化，转待审核）。
     */
    private static final String SKUS_COLLECTION = "skus";
}