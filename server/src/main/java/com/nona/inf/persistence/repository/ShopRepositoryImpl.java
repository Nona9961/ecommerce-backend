package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.entity.ShopCategory;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.inf.context.ThreadContext;
import com.nona.inf.persistence.converters.ShopCategoryConvertor;
import com.nona.inf.persistence.converters.ShopConvertor;
import com.nona.inf.persistence.po.catalog.ShopCategoryPO;
import com.nona.inf.persistence.po.catalog.ShopPO;
import com.nona.inf.persistence.repository.jpa.ShopCategoryJpaRepository;
import com.nona.inf.persistence.repository.jpa.ShopJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 店铺仓储落地：继承 {@link DifferRepository}（主表快照 + 变更追踪），
 * 主表 shop（id=店铺 ID，global 租户锚点）+ 从表 shop_category（tenant=shopId）。
 * <p>
 * 读：按主表主键加载（track 快照）+ getOther 经租户过滤加载本店铺从表行；
 * 保存：变更集驱动落库——分类新增插行、删除删行、字段变更整行更新；
 * 删除：deleteByID 级联删从表 + 主表，返回真实删除条数（根行）。
 * <p>
 * 从表写路径的租户列由写门禁按请求上下文注入（商家请求 tenant=当前店铺），
 * 读路径由 Hibernate 租户过滤保证 fail-closed（跨店铺加载不到分类行）。
 *
 * @author nona9961
 */
@Component
public class ShopRepositoryImpl extends DifferRepository<Shop, ShopPO, List<ShopCategoryPO>>
        implements ShopRepository {

    /**
     * 分类集合在聚合中的字段名（变更集路径解析用）
     */
    private static final String CATEGORIES_FIELD = "categories";

    /**
     * 店铺分类子表 JPA 仓储（从表加载与落库）
     */
    private final ShopCategoryJpaRepository shopCategoryJpaRepository;

    /**
     * 店铺分类行转换器
     */
    private final ShopCategoryConvertor shopCategoryConvertor;

    /**
     * 构造店铺仓储。
     *
     * @param repository               店铺主表 JPA 仓储
     * @param threadContext            请求级上下文（变更追踪器与快照）
     * @param convertor                店铺聚合转换器（主表 + 从表行集合）
     * @param changeTrackerProvider    变更追踪器提供者
     * @param shopCategoryJpaRepository 店铺分类子表 JPA 仓储
     * @param shopCategoryConvertor    店铺分类行转换器
     */
    public ShopRepositoryImpl(ShopJpaRepository repository,
                              ThreadContext threadContext,
                              ShopConvertor convertor,
                              ChangeTrackerProvider changeTrackerProvider,
                              ShopCategoryJpaRepository shopCategoryJpaRepository,
                              ShopCategoryConvertor shopCategoryConvertor) {
        super(repository, threadContext, convertor, changeTrackerProvider);
        this.shopCategoryJpaRepository = shopCategoryJpaRepository;
        this.shopCategoryConvertor = shopCategoryConvertor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从表行按排序值升序读出（展示顺序稳定），作为聚合装配的 other 输入。
     */
    @Override
    protected List<ShopCategoryPO> getOther(ShopPO po) {
        return shopCategoryJpaRepository.findByShopIdOrderByOrderNoAscIdAsc(po.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 聚合根主键 = 店铺 ID（租户锚点）。
     */
    @Override
    protected Long retrieveIDFromRoot(Shop root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 插入根行 + 全部从表行（新店首次落库）。
     */
    @Override
    protected void doInsert(Shop root) {
        repository.save(convertor.convertToPO(root));
        root.categoriesOrdered().forEach(category ->
                shopCategoryJpaRepository.save(shopCategoryConvertor.toPO(category)));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 变更集驱动落库：ensure 根行存在（首次新增即保存主表）→
     * 集合新增插行 → 集合删除删行 → 集合字段变更整行更新 →
     * 根行字段变更整行更新主表（字段覆盖，避免逐字段映射漂移）。
     */
    @Override
    protected void doUpdate(Shop root, ChangeSet changeSet) {
        if (!repository.existsById(root.getId())) {
            repository.save(convertor.convertToPO(root));
        }
        boolean rootRowDirty = false;
        for (final Change change : changeSet.getLeafChanges()) {
            if (!CATEGORIES_FIELD.equals(change.collectionFieldName())) {
                // 根行字段变更（path 形如 name）：整行更新主表
                rootRowDirty = true;
                continue;
            }
            if (change instanceof ItemAddedChange added) {
                final Long categoryId = extractIdentifier(added.addedItem());
                root.getCategoryById(categoryId)
                        .ifPresent(category -> shopCategoryJpaRepository.save(shopCategoryConvertor.toPO(category)));
            } else if (change instanceof ItemRemovedChange removed) {
                final Long categoryId = extractIdentifier(removed.removedItem());
                shopCategoryJpaRepository.deleteById(categoryId);
            } else {
                // ValueChange / ObjectFieldChange：整行更新（字段覆盖，避免逐字段映射漂移）
                saveChangedRow(root, change);
            }
        }
        if (rootRowDirty) {
            repository.save(convertor.convertToPO(root));
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除整店：委托 {@link #deleteByID}（按店铺 ID 级联删除）。
     */
    @Override
    public int delete(Shop shop) {
        return shop == null ? 0 : deleteByID(shop.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 级联删除：先删从表行（按店铺 ID），再删根行；返回根行删除条数
     * （0/1 真实语义，非契约形）。事务边界由用例层持有（REQUIRED 语义）。
     */
    @Override
    public int deleteByID(Long shopId) {
        if (shopId == null) {
            return 0;
        }
        shopCategoryJpaRepository.deleteByShopId(shopId);
        if (!repository.existsById(shopId)) {
            return 0;
        }
        repository.deleteById(shopId);
        return 1;
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
     * 从 root 中按变更路径里的集合项 ID 找到实体，整行更新。
     *
     * @param root   聚合根
     * @param change 字段变更（path 形如 categories[&lt;id&gt;].field）
     */
    private void saveChangedRow(Shop root, Change change) {
        final Long categoryId = extractIdFromPath(change.path());
        if (categoryId != null) {
            root.getCategoryById(categoryId)
                    .ifPresent(category -> shopCategoryJpaRepository.save(shopCategoryConvertor.toPO(category)));
        }
    }

    /**
     * 从变更路径提取集合项 ID（path 形如 categories[&lt;id&gt;].field）。
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