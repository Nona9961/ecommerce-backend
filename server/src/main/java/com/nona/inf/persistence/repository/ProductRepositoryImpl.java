package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.domain.catalog.entity.ProductImage;
import com.nona.domain.catalog.entity.Sku;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.inf.context.ThreadContext;
import com.nona.inf.persistence.converters.ProductAttributeConvertor;
import com.nona.inf.persistence.converters.ProductChildPos;
import com.nona.inf.persistence.converters.ProductConvertor;
import com.nona.inf.persistence.converters.ProductImageConvertor;
import com.nona.inf.persistence.converters.SkuConvertor;
import com.nona.inf.persistence.po.catalog.ProductAttributePO;
import com.nona.inf.persistence.po.catalog.ProductImagePO;
import com.nona.inf.persistence.po.catalog.ProductPO;
import com.nona.inf.persistence.repository.jpa.ProductAttributeJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductImageJpaRepository;
import com.nona.inf.persistence.repository.jpa.ProductJpaRepository;
import com.nona.inf.persistence.repository.jpa.SkuJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品仓储落地：继承 {@link DifferRepository}（主表快照 + 变更追踪），
 * 主表 product（id=商品 ID，tenant=shopId）+ 从表 product_image /
 * product_attribute / product_sku（均为 tenant=shopId）。
 * <p>
 * 读：按主表主键加载（track 快照）+ getOther 经租户过滤加载本商品三个
 * 从表集合（图片/属性按加入序、SKU 按 ID 升序——ID 升序近似创建序，维度
 * 调序重建后存活旧 SKU 与新 SKU 混合时读回序与模板展开序不一致，展示方
 * 需按组合序重排）；分页列表按店铺查询（与
 * 租户过滤共同定位行集），每行装配完整聚合（概要计数派生自集合）。保存：
 * 变更集驱动落库——集合新增插行、删除删行、字段变更整行更新、根字段变更
 * 整行更新主表（含规格模板 JSON 列）。删除：deleteByID 级联删三个从表 +
 * 主表，返回真实删除条数。引用存在性查询（类目/品牌）供平台侧禁用守卫
 * 使用——跨租户全局语义，事务内需读放行（@CrossTenant，放行职责在用例层）。
 * <p>
 * 从表写路径的租户列由写门禁按请求上下文注入（商家请求 tenant=当前店铺），
 * 读路径由 Hibernate 租户过滤保证 fail-closed（跨店铺加载不到商品行）。
 *
 * @author nona9961
 */
@Component
public class ProductRepositoryImpl extends DifferRepository<Product, ProductPO, ProductChildPos>
        implements ProductRepository {

    /**
     * 图片集合在聚合中的字段名（变更集路径解析用）
     */
    private static final String IMAGES_FIELD = "images";

    /**
     * 属性集合在聚合中的字段名（变更集路径解析用）
     */
    private static final String ATTRIBUTES_FIELD = "attributes";

    /**
     * SKU 集合在聚合中的字段名（变更集路径解析用）
     */
    private static final String SKUS_FIELD = "skus";

    /**
     * 商品主表 JPA 仓储（分页/统计/引用存在性查询）
     */
    private final ProductJpaRepository productJpaRepository;

    /**
     * 商品图片子表 JPA 仓储（从表加载与落库）
     */
    private final ProductImageJpaRepository imageJpaRepository;

    /**
     * 商品属性子表 JPA 仓储（从表加载与落库）
     */
    private final ProductAttributeJpaRepository attributeJpaRepository;

    /**
     * 商品 SKU 子表 JPA 仓储（从表加载与落库）
     */
    private final SkuJpaRepository skuJpaRepository;

    /**
     * 图片行转换器
     */
    private final ProductImageConvertor imageConvertor;

    /**
     * 属性行转换器
     */
    private final ProductAttributeConvertor attributeConvertor;

    /**
     * SKU 行转换器
     */
    private final SkuConvertor skuConvertor;

    /**
     * 构造商品仓储。
     *
     * @param repository             商品主表 JPA 仓储
     * @param threadContext          请求级上下文（变更追踪器与快照）
     * @param convertor              商品聚合转换器（主表 + 从表行集合）
     * @param changeTrackerProvider  变更追踪器提供者
     * @param imageJpaRepository     图片子表 JPA 仓储
     * @param attributeJpaRepository 属性子表 JPA 仓储
     * @param imageConvertor         图片行转换器
     * @param attributeConvertor     属性行转换器
     * @param skuJpaRepository       SKU 子表 JPA 仓储
     * @param skuConvertor           SKU 行转换器
     */
    public ProductRepositoryImpl(ProductJpaRepository repository,
                                 ThreadContext threadContext,
                                 ProductConvertor convertor,
                                 ChangeTrackerProvider changeTrackerProvider,
                                 ProductImageJpaRepository imageJpaRepository,
                                 ProductAttributeJpaRepository attributeJpaRepository,
                                 ProductImageConvertor imageConvertor,
                                 ProductAttributeConvertor attributeConvertor,
                                 SkuJpaRepository skuJpaRepository,
                                 SkuConvertor skuConvertor) {
        super(repository, threadContext, convertor, changeTrackerProvider);
        this.productJpaRepository = repository;
        this.imageJpaRepository = imageJpaRepository;
        this.attributeJpaRepository = attributeJpaRepository;
        this.imageConvertor = imageConvertor;
        this.attributeConvertor = attributeConvertor;
        this.skuJpaRepository = skuJpaRepository;
        this.skuConvertor = skuConvertor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从表行按加入序读出（图片/属性分别按 ID 升序，稳定；SKU 同按 ID 升序），
     * 作为聚合装配的
     * other 输入。
     */
    @Override
    protected ProductChildPos getOther(ProductPO po) {
        return new ProductChildPos(
                imageJpaRepository.findByProductIdOrderByIdAsc(po.getId()),
                attributeJpaRepository.findByProductIdOrderByIdAsc(po.getId()),
                skuJpaRepository.findByProductIdOrderByIdAsc(po.getId()));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 聚合根主键 = 商品 ID。
     */
    @Override
    protected Long retrieveIDFromRoot(Product root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 插入根行 + 全部从表行（新草稿首次落库）。
     */
    @Override
    protected void doInsert(Product root) {
        repository.save(convertor.convertToPO(root));
        root.imagesOrdered().forEach(image ->
                imageJpaRepository.save(imageConvertor.toPO(image)));
        root.attributesOrdered().forEach(attribute ->
                attributeJpaRepository.save(attributeConvertor.toPO(attribute)));
        root.skusOrdered().forEach(sku ->
                skuJpaRepository.save(skuConvertor.toPO(sku)));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 变更集驱动落库：ensure 根行存在（首次新增即保存主表）→ 图片/属性/SKU
     * 集合新增插行、删除删行、字段变更整行更新 → 根行字段变更整行更新主表
     * （字段覆盖，避免逐字段映射漂移）。
     */
    @Override
    protected void doUpdate(Product root, ChangeSet changeSet) {
        if (!repository.existsById(root.getId())) {
            repository.save(convertor.convertToPO(root));
        }
        boolean rootRowDirty = false;
        for (final Change change : changeSet.getLeafChanges()) {
            if (IMAGES_FIELD.equals(change.collectionFieldName())) {
                dispatchImageChange(root, change);
            } else if (ATTRIBUTES_FIELD.equals(change.collectionFieldName())) {
                dispatchAttributeChange(root, change);
            } else if (SKUS_FIELD.equals(change.collectionFieldName())) {
                dispatchSkuChange(root, change);
            } else {
                rootRowDirty = true;
            }
        }
        if (rootRowDirty) {
            repository.save(convertor.convertToPO(root));
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除整件商品：委托 {@link #deleteByID}（按商品 ID 级联删除）。
     */
    @Override
    public int delete(Product product) {
        return product == null ? 0 : deleteByID(product.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 级联删除：先删三个从表行（按商品 ID），再删根行；返回根行删除条数
     * （0/1 真实语义，非契约形）。事务边界由用例层持有（REQUIRED 语义）。
     */
    @Override
    public int deleteByID(Long productId) {
        if (productId == null) {
            return 0;
        }
        imageJpaRepository.deleteByProductId(productId);
        attributeJpaRepository.deleteByProductId(productId);
        skuJpaRepository.deleteByProductId(productId);
        if (!repository.existsById(productId)) {
            return 0;
        }
        repository.deleteById(productId);
        return 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Product> listByShopPaged(Long shopId, int offset, int limit) {
        final Page<ProductPO> page = productJpaRepository.findByShopIdOrderByIdDesc(
                shopId, pageRequest(offset, limit));
        return page.stream().map(this::assemble).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countByShop(Long shopId) {
        return productJpaRepository.countByShopId(shopId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean existsByCategoryId(Long categoryId) {
        return productJpaRepository.existsByCategoryId(categoryId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean existsByBrandId(Long brandId) {
        return productJpaRepository.existsByBrandId(brandId);
    }

    /**
     * 按变更节点分发图片集合变更：新增插行、删除删行、字段变更整行更新。
     *
     * @param root   聚合根
     * @param change 集合变更节点
     */
    private void dispatchImageChange(Product root, Change change) {
        if (change instanceof ItemAddedChange added) {
            final Long imageId = extractIdentifier(added.addedItem());
            root.getImageById(imageId).ifPresent(image ->
                    imageJpaRepository.save(imageConvertor.toPO(image)));
        } else if (change instanceof ItemRemovedChange removed) {
            final Long imageId = extractIdentifier(removed.removedItem());
            imageJpaRepository.deleteById(imageId);
        } else {
            saveChangedImageRow(root, change);
        }
    }

    /**
     * 按变更节点分发属性集合变更：新增插行、删除删行、字段变更整行更新。
     *
     * @param root   聚合根
     * @param change 集合变更节点
     */
    private void dispatchAttributeChange(Product root, Change change) {
        if (change instanceof ItemAddedChange added) {
            final Long attributeId = extractIdentifier(added.addedItem());
            root.getAttributeById(attributeId).ifPresent(attribute ->
                    attributeJpaRepository.save(attributeConvertor.toPO(attribute)));
        } else if (change instanceof ItemRemovedChange removed) {
            final Long attributeId = extractIdentifier(removed.removedItem());
            attributeJpaRepository.deleteById(attributeId);
        } else {
            saveChangedAttributeRow(root, change);
        }
    }

    /**
     * 从 root 中按变更路径里的图片 ID 找到实体，整行更新。
     *
     * @param root   聚合根
     * @param change 字段变更（path 形如 images[&lt;id&gt;].field）
     */
    private void saveChangedImageRow(Product root, Change change) {
        final Long imageId = extractIdFromPath(change.path());
        if (imageId != null) {
            root.getImageById(imageId)
                    .ifPresent(image -> imageJpaRepository.save(imageConvertor.toPO(image)));
        }
    }

    /**
     * 从 root 中按变更路径里的属性 ID 找到实体，整行更新。
     *
     * @param root   聚合根
     * @param change 字段变更（path 形如 attributes[&lt;id&gt;].field）
     */
    private void saveChangedAttributeRow(Product root, Change change) {
        final Long attributeId = extractIdFromPath(change.path());
        if (attributeId != null) {
            root.getAttributeById(attributeId)
                    .ifPresent(attribute -> attributeJpaRepository.save(attributeConvertor.toPO(attribute)));
        }
    }

    /**
     * 按变更节点分发 SKU 集合变更：新增插行、删除删行、字段变更整行更新。
     *
     * @param root   聚合根
     * @param change 集合变更节点
     */
    private void dispatchSkuChange(Product root, Change change) {
        if (change instanceof ItemAddedChange added) {
            final Long skuId = extractIdentifier(added.addedItem());
            root.getSkuById(skuId).ifPresent(sku ->
                    skuJpaRepository.save(skuConvertor.toPO(sku)));
        } else if (change instanceof ItemRemovedChange removed) {
            final Long skuId = extractIdentifier(removed.removedItem());
            skuJpaRepository.deleteById(skuId);
        } else {
            saveChangedSkuRow(root, change);
        }
    }

    /**
     * 从 root 中按变更路径里的 SKU ID 找到实体，整行更新。
     *
     * @param root   聚合根
     * @param change 字段变更（path 形如 skus[&lt;id&gt;].field）
     */
    private void saveChangedSkuRow(Product root, Change change) {
        final Long skuId = extractIdFromPath(change.path());
        if (skuId != null) {
            root.getSkuById(skuId)
                    .ifPresent(sku -> skuJpaRepository.save(skuConvertor.toPO(sku)));
        }
    }

    /**
     * 按 PO 加载完整聚合（track 快照 + 从表集合装配）。
     *
     * @param po 商品根行
     * @return 商品聚合
     */
    private Product assemble(ProductPO po) {
        return convertor.convertToRoot(po, getOther(po));
    }

    /**
     * 组装分页请求（加入序倒序——新商品在前）。
     *
     * @param offset 首条偏移量
     * @param limit  每页条数
     * @return 分页请求
     */
    private static PageRequest pageRequest(int offset, int limit) {
        return PageRequest.of(offset / limit, limit, Sort.by(Sort.Direction.DESC, "id"));
    }

    /**
     * 从变更节点提取集合项标识（Identifier 提取器按实体 id 注册；
     * 快照反序列化可能以 Integer 形态持有小值 id，统一按数字取值）。
     *
     * @param node 变更节点（ValueNode 子类型）
     * @return 集合项 ID
     */
    private static Long extractIdentifier(com.nona.changeTracking.domain.model.snapshot.ValueNode node) {
        if (node instanceof ObjectNode objectNode
                && objectNode.identifier() instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    /**
     * 从变更路径提取集合项 ID（path 形如 images[&lt;id&gt;].field）。
     *
     * @param path 变更路径
     * @return 集合项 ID；无法解析返回 null
     */
    private static Long extractIdFromPath(String path) {
        final int start = path.indexOf('[');
        final int end = path.indexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return Long.parseLong(path.substring(start + 1, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
