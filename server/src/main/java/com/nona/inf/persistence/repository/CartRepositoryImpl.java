package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.nona.domain.order.entity.Cart;
import com.nona.domain.order.entity.CartItem;
import com.nona.domain.order.repo.CartRepository;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.converters.CartConvertor;
import com.nona.inf.persistence.converters.CartItemConvertor;
import com.nona.inf.persistence.po.order.CartPO;
import com.nona.inf.persistence.po.order.CartItemPO;
import com.nona.inf.persistence.repository.jpa.CartItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.CartJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 购物车仓储落地：继承 {@link DifferRepository}（主表快照 + 变更追踪），
 * 主表 cart（id=购物车独立主键 + buyer_id 业务关联）+ 从表 cart_item
 * （cart_id 关联、(buyer_id, sku_id) 唯一）。
 * <p>
 * 读：按业务关联买家查主表行 → 走主键加载（track 快照）+ getOther 加载
 * 从表行；保存：变更集驱动落库——条目新增插行、删除删行、字段变更整行
 * 更新。删除：deleteByID 级联删从表 + 主表，返回真实删除条数（根行）。
 * 买家写锁：借道账号行悲观锁，串行化同买家购物车写事务。
 * <p>
 * @author nona9961
 */
@Component
public class CartRepositoryImpl extends DifferRepository<Cart, CartPO, List<CartItemPO>>
        implements CartRepository {

    /**
     * 条目集合在聚合中的字段名（变更集路径解析用）
     */
    private static final String ITEMS_FIELD = "items";

    /**
     * 条目 JPA 仓储（从表加载与落库、买家写锁）
     */
    private final CartItemJpaRepository cartItemJpaRepository;

    /**
     * 主表 JPA 仓储（业务关联查询）
     */
    private final CartJpaRepository cartJpaRepository;

    /**
     * 条目行转换器
     */
    private final CartItemConvertor cartItemConvertor;

    /**
     * 构造购物车仓储。
     *
     * @param repository            购物车主表 JPA 仓储
     * @param convertor             购物车聚合转换器（主表 + 从表行集合）
     * @param changeTrackerProvider 变更追踪器提供者
     * @param cartItemJpaRepository 条目 JPA 仓储
     * @param cartJpaRepository     购物车主表 JPA 仓储（业务关联查询）
     * @param cartItemConvertor     条目行转换器
     */
    public CartRepositoryImpl(CartJpaRepository repository,
                              CartConvertor convertor,
                              ChangeTrackerProvider changeTrackerProvider,
                              CartItemJpaRepository cartItemJpaRepository,
                              CartJpaRepository cartJpaRepository,
                              CartItemConvertor cartItemConvertor) {
        super(repository, convertor, changeTrackerProvider);
        this.cartItemJpaRepository = cartItemJpaRepository;
        this.cartJpaRepository = cartJpaRepository;
        this.cartItemConvertor = cartItemConvertor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从表行按加载序读出（ID 升序），作为聚合恢复的 other 输入。
     */
    @Override
    protected List<CartItemPO> getOther(CartPO po) {
        return cartItemJpaRepository.findByCartIdOrderByIdAsc(po.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 聚合根主键 = 购物车独立主键（cartId）。
     */
    @Override
    protected Long retrieveIDFromRoot(Cart root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 插入根行 + 全部从表行（新购物车首次落库）。
     */
    @Override
    protected void doInsert(Cart root) {
        repository.save(convertor.convertToPO(root));
        root.snapshot().forEach(item -> cartItemJpaRepository.save(cartItemConvertor.toPO(item)));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 变更集驱动落库：确保根行存在（空车首次变更场景）→ 集合新增插行 →
     * 集合删除删行 → 字段变更整行更新。
     */
    @Override
    protected void doUpdate(Cart root, com.nona.changeTracking.domain.model.changeset.ChangeSet changeSet) {
        if (!repository.existsById(root.getId())) {
            repository.save(convertor.convertToPO(root));
        }
        for (final Change change : changeSet.getLeafChanges()) {
            if (!ITEMS_FIELD.equals(change.collectionFieldName())) {
                continue;
            }
            if (change instanceof ItemAddedChange added) {
                final Long itemId = extractIdentifier(added.addedItem());
                findItem(root, itemId)
                        .ifPresent(item -> cartItemJpaRepository.save(cartItemConvertor.toPO(item)));
            } else if (change instanceof ItemRemovedChange removed) {
                final Long itemId = extractIdentifier(removed.removedItem());
                cartItemJpaRepository.deleteById(itemId);
            } else {
                // ValueChange / ObjectFieldChange：整行更新，避免逐字段映射漂移
                saveChangedRow(root, change);
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 按业务关联买家加载：返回空车时聚合始终存在语义（空车实体承载）。
     */
    @Override
    public Cart getByBuyerId(Long buyerId) {
        final Optional<CartPO> po = cartJpaRepository.findByBuyerId(buyerId);
        if (po.isPresent()) {
            return getByID(po.get().getId());
        }
        final Cart empty = new Cart(IDUtils.generateID(), buyerId);
        getOrCreateChangeTracker().track(empty);
        TrackingContext.scope().getSnapshots().put(empty.getId(), empty);
        return empty;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 买家维度悲观锁：借道账号行加写锁（由调用方事务持有至提交）。
     */
    @Override
    public void lockBuyer(Long buyerId) {
        cartItemJpaRepository.lockBuyerAccount(buyerId);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除整车：委托 {@link #deleteByID}（按购物车主键级联删除）。
     */
    @Override
    public int delete(Cart cart) {
        return cart == null ? 0 : deleteByID(cart.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 级联删除：先删从表行（按购物车主键），再删根行；返回根行删除条数
     * （0/1 真实语义，非契约形）。事务边界由用例层持有（REQUIRED 语义）。
     */
    @Override
    public int deleteByID(Long cartId) {
        if (cartId == null) {
            return 0;
        }
        cartItemJpaRepository.deleteByCartId(cartId);
        if (!repository.existsById(cartId)) {
            return 0;
        }
        repository.deleteById(cartId);
        return 1;
    }

    /**
     * 从变更节点提取集合项标识（Identifier 提取器按实体 id 注册）。
     *
     * @param node 变更节点（ObjectNode）
     * @return 集合项 ID
     */
    private static Long extractIdentifier(ValueNode node) {
        if (node instanceof ObjectNode objectNode) {
            return (Long) objectNode.identifier();
        }
        return null;
    }

    /**
     * 从聚合条目快照中按条目 ID 定位实体（整行更新/新增落库前取最新态）。
     *
     * @param root   购物车聚合
     * @param itemId 条目 ID
     * @return 条目；不存在返回空
     */
    private static Optional<CartItem> findItem(Cart root, Long itemId) {
        return root.snapshot().stream().filter(item -> item.getId().equals(itemId)).findFirst();
    }

    /**
     * 从 root 中按变更路径里的集合项 ID 找到条目，整行更新。
     *
     * @param root   购物车聚合
     * @param change 字段变更（path 形如 items[&lt;id&gt;].field）
     */
    private void saveChangedRow(Cart root, Change change) {
        final Long itemId = extractIdFromPath(change.path());
        if (itemId != null) {
            findItem(root, itemId)
                    .ifPresent(item -> cartItemJpaRepository.save(cartItemConvertor.toPO(item)));
        }
    }

    /**
     * 从变更路径提取集合项 ID（path 形如 items[&lt;id&gt;].field）。
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