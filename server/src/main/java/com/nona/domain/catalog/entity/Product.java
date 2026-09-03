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
 *         （未定价合法，提交审核时必填校验属后续阶段）非空时为正；</li>
 *     <li>创建即 DRAFT（草稿可保存不生效）——状态机与审核属后续阶段。</li>
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
     * 200 量级，一期商品体量足够覆盖多规格场景，同时约束接口响应与
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
     * 商品状态（一期恒 DRAFT——草稿态）
     */
    private final ProductStatus status;

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
     *
     * @param name        新名称
     * @param description 新描述（可空）
     * @param categoryId  新平台类目 ID（可空）
     * @param brandId     新品牌 ID（可空）
     */
    public void updateInfo(String name, String description, Long categoryId, Long brandId) {
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
            addImage(new ProductImage(image.getId(), id, image.getUrl(), image.isPrimary()));
        }
        attributes.clear();
        for (final ProductAttribute attribute : content.attributes()) {
            addAttribute(new ProductAttribute(attribute.getId(), id, attribute.getKey(), attribute.getValue()));
        }
    }

    /**
     * 新增图片引用（不变量 2/3/5）：URL 必填非空；ID 在聚合内唯一；
     * 请求设为主图时自动清除原主图标记（至多一条主图）。
     *
     * @param image 图片引用（工厂创建，待入聚合）
     */
    public void addImage(ProductImage image) {
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
     * 删除图片引用（不变量 2）：目标必须存在；主图被删后主图位清空，
     * 其余图片标记不变。
     *
     * @param imageId 图片引用 ID
     */
    public void removeImage(Long imageId) {
        final ProductImage image = requireExistingImage(imageId);
        images.remove(image);
    }

    /**
     * 设置主图（不变量 2）：目标必须存在；清除原主图标记后设目标为主图。
     *
     * @param imageId 图片引用 ID
     */
    public void setPrimaryImage(Long imageId) {
        final ProductImage image = requireExistingImage(imageId);
        images.forEach(existing -> existing.markPrimary(false));
        image.markPrimary(true);
    }

    /**
     * 新增自定义属性（不变量 4/5）：键必填非空；键在聚合内唯一；ID 唯一。
     *
     * @param attribute 属性键值对（工厂创建，待入聚合）
     */
    public void addAttribute(ProductAttribute attribute) {
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
     * 唯一（排除自身，改回自身键合法）；值可空。
     *
     * @param attributeId 属性 ID
     * @param key         新键
     * @param value       新值（可空）
     */
    public void updateAttribute(Long attributeId, String key, String value) {
        final ProductAttribute attribute = requireExistingAttribute(attributeId);
        if (key == null || key.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_ATTRIBUTE_KEY_BLANK.code(), "属性键不能为空");
        }
        requireKeyFree(key, attributeId);
        attribute.update(key, value);
    }

    /**
     * 删除自定义属性：目标必须存在。
     *
     * @param attributeId 属性 ID
     */
    public void removeAttribute(Long attributeId) {
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
     * 本商品（不属于按不存在呈现，不泄露归属）。
     *
     * @param skuId SKU ID
     * @param price 新价格（分；null=清除价格复位未定价）
     */
    public void updateSkuPrice(Long skuId, Long price) {
        final Sku sku = requireExistingSku(skuId);
        if (price != null && price <= 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SKU_PRICE_INVALID.code(), "SKU价格必须为正整数");
        }
        sku.updatePrice(price);
    }

    /**
     * 切换 SKU 启用状态（停用 SKU 不出售；草稿期无生效路径——商品状态
     * 恒 DRAFT，启停仅记录意图，状态机与上架生效属后续阶段）。
     *
     * @param skuId   SKU ID
     * @param enabled 是否启用
     */
    public void setSkuEnabled(Long skuId, boolean enabled) {
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
}