package com.nona.inf.persistence.repository;

import com.nona.domain.identity.entity.Address;
import com.nona.domain.identity.entity.AddressBook;
import com.nona.domain.identity.repo.AddressBookRepository;
import com.nona.inf.persistence.converters.AddressConvertor;
import com.nona.inf.persistence.po.identity.AddressPO;
import com.nona.inf.persistence.repository.jpa.AddressJpaRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 地址簿仓储落地：JPA 行级同步 address 表，实现域仓储契约 {@link AddressBookRepository}。
 * <p>
 * 读取按买家全量加载（findByAccountIdOrderByIdAsc）；保存与库内现状做行级 diff：
 * 簿内存在而库内没有的行插入，字段不一致的行更新，库内剩余的行删除（最小化落库）。
 * 地址簿是单表集合形态（无主表行），不套用 DifferRepository 的主表快照模板——与
 * 账号-店铺关联（AccountShopRel）同类处理：直接包装 Spring Data 仓储。
 *
 * @author nona9961
 */
@Component
public class AddressBookRepositoryImpl implements AddressBookRepository {

    /**
     * 地址 JPA 仓储
     */
    private final AddressJpaRepository jpaRepository;

    /**
     * 地址实体 ↔ PO 转换器
     */
    private final AddressConvertor convertor;

    /**
     * 构造仓储实现。
     *
     * @param jpaRepository 地址 JPA 仓储
     * @param convertor     地址转换器
     */
    public AddressBookRepositoryImpl(AddressJpaRepository jpaRepository, AddressConvertor convertor) {
        this.jpaRepository = jpaRepository;
        this.convertor = convertor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 聚合标识 = 买家账号 ID，与 {@link #getByAccountId} 同语义。
     */
    @Override
    public AddressBook getByID(Long accountId) {
        return getByAccountId(accountId);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 无任何地址时返回空簿（聚合始终存在；买家账号存在性由登录链路保证）。
     */
    @Override
    public AddressBook getByAccountId(Long accountId) {
        final List<Address> addresses = jpaRepository.findByAccountIdOrderByIdAsc(accountId).stream()
                .map(convertor::toDomain)
                .toList();
        final AddressBook book = new AddressBook(accountId);
        addresses.forEach(book::add);
        return book;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 行级 diff 落库：比较库内行与簿内地址（按 ID 对齐），
     * 新增/字段变更行 save，库内剩余行删除；无任何差异时不执行 SQL。
     */
    @Override
    public boolean save(AddressBook book) {
        Objects.requireNonNull(book, "地址簿不能为空");
        final Map<Long, AddressPO> existingById = new HashMap<>();
        jpaRepository.findByAccountIdOrderByIdAsc(book.getAccountId())
                .forEach(po -> existingById.put(po.getId(), po));

        boolean changed = false;
        for (final Address address : book.snapshot()) {
            final AddressPO existing = existingById.remove(address.getId());
            if (existing == null || !sameFields(existing, address)) {
                jpaRepository.save(convertor.toPO(address));
                changed = true;
            }
        }
        if (!existingById.isEmpty()) {
            jpaRepository.deleteAll(existingById.values());
            changed = true;
        }
        return changed;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 地址簿不整体删除（删除地址走簿内 remove 后 save）；返回 0 保持契约形。
     */
    @Override
    public int delete(AddressBook book) {
        return 0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 地址簿不整体删除（删除地址走簿内 remove 后 save）；返回 0 保持契约形。
     */
    @Override
    public int deleteByID(Long id) {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void lockBuyer(Long accountId) {
        jpaRepository.lockBuyerAccount(accountId);
    }

    /**
     * 比较库内行与领域实体的业务字段（默认标记在内；审计时间戳不参与比较）。
     *
     * @param po      库内行
     * @param address 领域实体
     * @return 字段全一致返回 true
     */
    private static boolean sameFields(AddressPO po, Address address) {
        return Objects.equals(po.getRecipient(), address.getRecipient())
                && Objects.equals(po.getPhone(), address.getPhone())
                && Objects.equals(po.getProvince(), address.getProvince())
                && Objects.equals(po.getCity(), address.getCity())
                && Objects.equals(po.getDistrict(), address.getDistrict())
                && Objects.equals(po.getDetail(), address.getDetail())
                && Boolean.TRUE.equals(po.getIsDefault()) == address.isDefault();
    }
}