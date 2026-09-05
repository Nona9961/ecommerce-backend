package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.domain.identity.entity.Address;
import com.nona.domain.identity.entity.AddressBook;
import com.nona.domain.identity.repo.AddressBookRepository;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.converters.AddressBookConvertor;
import com.nona.inf.persistence.converters.AddressConvertor;
import com.nona.inf.persistence.po.identity.AddressBookPO;
import com.nona.inf.persistence.po.identity.AddressPO;
import com.nona.inf.persistence.repository.jpa.AddressBookJpaRepository;
import com.nona.inf.persistence.repository.jpa.AddressJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 地址簿仓储落地：继承 {@link DifferRepository}（主表快照 + 变更追踪），
 * 主表 address_book（id=簿独立主键 + account_id 业务关联）+ 从表 address（book_id 关联）。
 * <p>
 * 读：按业务关联账号查主表行 → 走主键加载（track 快照）+ getOther 加载从表行；
 * 保存：变更集驱动落库——集合项新增插行、删除删行、字段变更整行更新。
 * 删除：deleteByID 级联删从表 + 主表，返回真实删除条数（根行）。
 *
 * @author nona9961
 */
@Component
public class AddressBookRepositoryImpl extends DifferRepository<AddressBook, AddressBookPO, List<AddressPO>>
        implements AddressBookRepository {

    /**
     * 从表集合在聚合中的字段名（变更集路径解析用）
     */
    private static final String ADDRESSES_FIELD = "addresses";

    /**
     * 地址子表 JPA 仓储（从表加载与落库）
     */
    private final AddressJpaRepository addressJpaRepository;

    /**
     * 地址簿主表 JPA 仓储（业务关联查询）
     */
    private final AddressBookJpaRepository addressBookJpaRepository;

    /**
     * 地址行转换器
     */
    private final AddressConvertor addressConvertor;

    /**
     * 构造地址簿仓储。
     *
     * @param repository             地址簿主表 JPA 仓储
     * @param convertor              地址簿聚合转换器（主表 + 从表行集合）
     * @param changeTrackerProvider  变更追踪器提供者
     * @param addressJpaRepository   地址子表 JPA 仓储
     * @param addressBookJpaRepository 地址簿主表 JPA 仓储（业务关联查询）
     * @param addressConvertor       地址行转换器
     */
    public AddressBookRepositoryImpl(AddressBookJpaRepository repository,
                                     AddressBookConvertor convertor,
                                     ChangeTrackerProvider changeTrackerProvider,
                                     AddressJpaRepository addressJpaRepository,
                                     AddressBookJpaRepository addressBookJpaRepository,
                                     AddressConvertor addressConvertor) {
        super(repository, convertor, changeTrackerProvider);
        this.addressJpaRepository = addressJpaRepository;
        this.addressBookJpaRepository = addressBookJpaRepository;
        this.addressConvertor = addressConvertor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从表行按加载序读出（ID 升序），作为聚合装配的 other 输入。
     */
    @Override
    protected List<AddressPO> getOther(AddressBookPO po) {
        return addressJpaRepository.findByBookIdOrderByIdAsc(po.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 聚合根主键 = 簿独立主键（bookId）。
     */
    @Override
    protected Long retrieveIDFromRoot(AddressBook root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 插入根行 + 全部从表行（新簿首次落库）。
     */
    @Override
    protected void doInsert(AddressBook root) {
        repository.save(convertor.convertToPO(root));
        root.snapshot().forEach(address -> addressJpaRepository.save(addressConvertor.toPO(address)));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 变更集驱动落库：ensure 根行存在（空簿首次 add 的场景）→
     * 集合新增插行 → 集合删除删行 → 字段变更整行更新。
     */
    @Override
    protected void doUpdate(AddressBook root, ChangeSet changeSet) {
        if (!repository.existsById(root.getId())) {
            repository.save(convertor.convertToPO(root));
        }
        for (final Change change : changeSet.getLeafChanges()) {
            if (!ADDRESSES_FIELD.equals(change.collectionFieldName())) {
                continue;
            }
            if (change instanceof ItemAddedChange added) {
                final Long addressId = extractIdentifier(added.addedItem());
                root.getById(addressId)
                        .ifPresent(address -> addressJpaRepository.save(addressConvertor.toPO(address)));
            } else if (change instanceof ItemRemovedChange removed) {
                final Long addressId = extractIdentifier(removed.removedItem());
                addressJpaRepository.deleteById(addressId);
            } else {
                // ValueChange / ObjectFieldChange：整行更新（字段覆盖，避免逐字段映射漂移）
                saveChangedRow(root, change);
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除整簿：委托 {@link #deleteByID}（按簿主键级联删除）。
     */
    @Override
    public int delete(AddressBook book) {
        return book == null ? 0 : deleteByID(book.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 级联删除：先删从表行（按簿主键），再删根行；返回根行删除条数
     * （0/1 真实语义，非契约形）。事务边界由用例层持有（REQUIRED 语义）。
     */
    @Override
    public int deleteByID(Long bookId) {
        if (bookId == null) {
            return 0;
        }
        addressJpaRepository.deleteByBookId(bookId);
        if (!repository.existsById(bookId)) {
            return 0;
        }
        repository.deleteById(bookId);
        return 1;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 按业务关联账号加载：无簿时返回空簿（聚合始终存在语义）；
     * 有簿时经主键路径加载（track 快照 + 装配）。
     */
    @Override
    public AddressBook getByAccountId(Long accountId) {
        final Optional<AddressBookPO> po = addressBookJpaRepository.findByAccountId(accountId);
        if (po.isPresent()) {
            return getByID(po.get().getId());
        }
        final AddressBook empty = new AddressBook(com.nona.util.IDUtils.generateID(), accountId);
        getOrCreateChangeTracker().track(empty);
        TrackingContext.scope().getSnapshots().put(empty.getId(), empty);
        return empty;
    }

    /**
     * 从变更节点提取集合项标识（Identifier 提取器按实体 id 注册）。
     *
     * @param node 变更节点（ObjectNode）
     * @return 集合项 ID
     */
    private static Long extractIdentifier(com.nona.changeTracking.domain.model.snapshot.ValueNode node) {
        if (node instanceof ObjectNode objectNode) {
            return (Long) objectNode.identifier();
        }
        return null;
    }

    /**
     * 从 root 中按变更路径里的集合项 ID 找到实体，整行更新。
     *
     * @param root   聚合根
     * @param change 字段变更（path 形如 addresses[&lt;id&gt;].field）
     */
    private void saveChangedRow(AddressBook root, Change change) {
        final Long addressId = extractIdFromPath(change.path());
        if (addressId != null) {
            root.getById(addressId)
                    .ifPresent(address -> addressJpaRepository.save(addressConvertor.toPO(address)));
        }
    }

    /**
     * 从变更路径提取集合项 ID（path 形如 addresses[&lt;id&gt;].field）。
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

    /**
     * {@inheritDoc}
     * <p>
     * 买家维度悲观锁：对账号行加写锁，串行化同一买家的地址写操作。
     * 由调用方事务持有至提交（调用方需处于活动事务中）。
     */
    @Override
    public void lockBuyer(Long accountId) {
        addressJpaRepository.lockBuyerAccount(accountId);
    }
}