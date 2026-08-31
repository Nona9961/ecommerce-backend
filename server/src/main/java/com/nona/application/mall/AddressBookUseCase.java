package com.nona.application.mall;

import com.nona.api.mall.AddressRequest;
import com.nona.api.mall.AddressResponse;
import com.nona.domain.identity.entity.Address;
import com.nona.domain.identity.entity.AddressBook;
import com.nona.domain.identity.factory.AddressBookFactory;
import com.nona.domain.identity.repo.AddressBookRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 买家地址簿用例：地址 CRUD 与默认地址设置的编排（买家维度，事务边界所在）。
 * <p>
 * 买家身份由认证上下文提供（controller 从 ThreadContext 取当前账号 ID 传入），
 * 本用例不感知 token 机制。写入路径（新增/编辑/删除/设置默认）统一先取买家维度
 * 写锁（{@link AddressBookRepository#lockBuyer}）——串行化同一买家的地址写事务，
 * 默认地址唯一性在并发窗口下也能收敛；随后加载聚合、执行领域操作、落库，全在
 * 一个事务内完成。目标地址不属于当前买家时一律呈现为不存在（404，不泄露归属）。
 *
 * @author nona9961
 */
@Service
public class AddressBookUseCase {

    /**
     * 地址簿仓储
     */
    private final AddressBookRepository addressBookRepository;

    /**
     * 地址簿聚合工厂
     */
    private final AddressBookFactory addressBookFactory;

    /**
     * 构造地址簿用例。
     *
     * @param addressBookRepository 地址簿仓储
     * @param addressBookFactory    地址簿工厂
     */
    public AddressBookUseCase(AddressBookRepository addressBookRepository,
                              AddressBookFactory addressBookFactory) {
        this.addressBookRepository = addressBookRepository;
        this.addressBookFactory = addressBookFactory;
    }

    /**
     * 地址列表：加载当前买家地址簿，映射为响应形态（读路径不建根、不加锁）。
     *
     * @param accountId 买家账号 ID
     * @return 地址列表；无地址为空列表
     */
    public List<AddressResponse> list(Long accountId) {
        return addressBookRepository.getByAccountId(accountId).snapshot().stream()
                .map(AddressBookUseCase::toResponse)
                .toList();
    }

    /**
     * 新增地址：买家锁内加载簿 → 工厂创建 → 聚合追加（带默认标记时自动让位旧默认）→ 落库。
     *
     * @param accountId 买家账号 ID
     * @param request   地址信息
     * @return 新建地址（含分配的地址 ID）
     */
    @Transactional
    public AddressResponse add(Long accountId, AddressRequest request) {
        addressBookRepository.lockBuyer(accountId);
        final AddressBook book = addressBookRepository.getByAccountId(accountId);
        final Address address = addressBookFactory.createAddress(book,
                request.recipient(), request.phone(), request.province(),
                request.city(), request.district(), request.detail(),
                Boolean.TRUE.equals(request.isDefault()));
        book.add(address);
        addressBookRepository.save(book);
        return toResponse(address);
    }

    /**
     * 编辑地址：买家锁内更新地址字段（默认标记不被编辑触碰，保持簿内现值）。
     *
     * @param accountId 买家账号 ID
     * @param addressId 地址 ID
     * @param request   新的地址信息
     * @return 更新后的地址
     */
    @Transactional
    public AddressResponse update(Long accountId, Long addressId, AddressRequest request) {
        addressBookRepository.lockBuyer(accountId);
        final AddressBook book = addressBookRepository.getByAccountId(accountId);
        final Address address = requireOwned(book, addressId);
        address.updateDetails(request.recipient(), request.phone(), request.province(),
                request.city(), request.district(), request.detail());
        book.update(address);
        addressBookRepository.save(book);
        return toResponse(address);
    }

    /**
     * 删除地址：买家锁内从簿移除；删除的是默认时聚合内自动提升第一条为默认。
     *
     * @param accountId 买家账号 ID
     * @param addressId 地址 ID
     */
    @Transactional
    public void delete(Long accountId, Long addressId) {
        addressBookRepository.lockBuyer(accountId);
        final AddressBook book = addressBookRepository.getByAccountId(accountId);
        requireOwned(book, addressId);
        book.remove(addressId);
        addressBookRepository.save(book);
    }

    /**
     * 设置默认地址：买家锁内迁移默认标记（旧默认让位；已是默认则幂等）。
     *
     * @param accountId 买家账号 ID
     * @param addressId 地址 ID
     */
    @Transactional
    public void setDefault(Long accountId, Long addressId) {
        addressBookRepository.lockBuyer(accountId);
        final AddressBook book = addressBookRepository.getByAccountId(accountId);
        requireOwned(book, addressId);
        book.setDefault(addressId);
        addressBookRepository.save(book);
    }

    /**
     * 按 ID 取簿内地址并断言归属（不属于当前买家即 404，不泄露归属信息）。
     *
     * @param book      地址簿
     * @param addressId 地址 ID
     * @return 地址
     */
    private static Address requireOwned(AddressBook book, Long addressId) {
        return book.getById(addressId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.IDENTITY_ADDRESS_NOT_FOUND.code(), "地址不存在", 404));
    }

    /**
     * 地址实体 → 响应体映射。
     *
     * @param address 地址实体
     * @return 响应体
     */
    private static AddressResponse toResponse(Address address) {
        return new AddressResponse(address.getId(), address.getRecipient(), address.getPhone(),
                address.getProvince(), address.getCity(), address.getDistrict(),
                address.getDetail(), address.isDefault());
    }
}