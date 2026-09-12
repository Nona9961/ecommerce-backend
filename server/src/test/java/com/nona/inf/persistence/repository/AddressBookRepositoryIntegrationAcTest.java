package com.nona.inf.persistence.repository;

import com.nona.domain.identity.entity.Address;
import com.nona.domain.identity.entity.AddressBook;
import com.nona.domain.identity.factory.AddressBookFactory;
import com.nona.domain.identity.repo.AddressBookRepository;
import com.nona.inf.persistence.repository.jpa.AddressBookJpaRepository;
import com.nona.inf.persistence.repository.jpa.AddressJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import com.nona.inf.context.TrackingContext;

/**
 * 地址簿仓储集成测试：聚合根主表（address_book）存在性、变更集驱动落库、
 * 删除语义（主表+子表级联）、空簿加载契约。
 * <p>
 * 与脚手架的 DDD 红线对应：聚合根必须有根表（address_book 一行=一簿，
 * 主键=簿独立主键 bookId，account_id 业务关联）；仓储继承 DifferRepository，
 * 读=track 快照、save=变更集落库；从表以 book_id（rootId）关联。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class AddressBookRepositoryIntegrationAcTest {

    /**
     * 地址簿仓储（被测对象：DifferRepository 子类）
     */
    @Autowired
    private AddressBookRepository addressBookRepository;

    /**
     * 地址簿主表 JPA（断言根表行）
     */
    @Autowired
    private AddressBookJpaRepository addressBookJpaRepository;

    /**
     * 地址子表 JPA（断言子表行）
     */
    @Autowired
    private AddressJpaRepository addressJpaRepository;

    /**
     * 地址簿聚合工厂
     */
    @Autowired
    private AddressBookFactory addressBookFactory;

    /**
     * 编程式事务模板（模拟用例层事务边界：删除是写路径，需在事务内）
     */
    @Autowired
    private TransactionTemplate tx;

    /**
     * 每用例前清空主表+子表（根表先行，避免外键/残留干扰）。
     */
    @BeforeEach
    void setUp() {
        addressJpaRepository.deleteAll();
        addressBookJpaRepository.deleteAll();
    }

    /**
     * 新增：save 后主表存在一根行（bookId 独立主键），子表一行，聚合可整体读回。
     */
    @Test
    @DisplayName("新增地址后主表存在根行且子表落库")
    void save_insertsRootRowAndChildRows() {
        TrackingContext.withScope(() -> {
            final AddressBook book = addressBookFactory.createBook(100L);
            book.add(addressBookFactory.createAddress(book, "张三", "13800138000",
                    "浙江省", "杭州市", "西湖区", "文一西路 100 号", true));
            addressBookRepository.save(book);

            assertThat(addressBookJpaRepository.existsById(book.getId())).isTrue();
            assertThat(addressBookJpaRepository.findByAccountId(100L)).isPresent();
            assertThat(addressJpaRepository.findByBookIdOrderByIdAsc(book.getId())).hasSize(1);

            final AddressBook loaded = addressBookRepository.getByAccountId(100L);
            assertThat(loaded.getAccountId()).isEqualTo(100L);
            assertThat(loaded.getId()).isEqualTo(book.getId());
            assertThat(loaded.snapshot()).hasSize(1);
            assertThat(loaded.getDefault()).isPresent();
    
        });
}

    /**
     * 更新：快照基线后修改字段，save 只落变更行（行数不变，字段更新）。
     */
    @Test
    @DisplayName("编辑地址后变更集驱动更新子表行")
    void save_updateAppliesChangedRow() {
        TrackingContext.withScope(() -> {
            final AddressBook book = addressBookFactory.createBook(200L);
            final Address first = addressBookFactory.createAddress(book, "张三", "13800138000",
                    "浙江省", "杭州市", "西湖区", "文一西路 100 号", true);
            final Address second = addressBookFactory.createAddress(book, "李四", "13900139000",
                    "浙江省", "宁波市", "海曙区", "中山东路 1 号", false);
            book.add(first);
            book.add(second);
            addressBookRepository.save(book);

            // 重新加载（建立快照基线）→ 修改 → save
            final AddressBook loaded = addressBookRepository.getByAccountId(200L);
            loaded.getById(first.getId()).orElseThrow().updateDetails(
                    "张三丰", "13700137000", "浙江省", "杭州市", "滨江区", "江南大道 2 号");
            addressBookRepository.save(loaded);

            final AddressBook reloaded = addressBookRepository.getByAccountId(200L);
            assertThat(reloaded.snapshot()).hasSize(2);
            assertThat(reloaded.getById(first.getId()).orElseThrow().getRecipient()).isEqualTo("张三丰");
            assertThat(reloaded.getById(second.getId()).orElseThrow().getRecipient()).isEqualTo("李四");
    
        });
}

    /**
     * 删除地址：变更集驱动删除子表行；根表行保留（簿仍存在）。
     */
    @Test
    @DisplayName("删除地址后子表行移除、根表行保留")
    void save_removeChildKeepsRootRow() {
        TrackingContext.withScope(() -> {
            final AddressBook book = addressBookFactory.createBook(300L);
            final Address first = addressBookFactory.createAddress(book, "张三", "13800138000",
                    "浙江省", "杭州市", "西湖区", "文一西路 100 号", true);
            book.add(first);
            addressBookRepository.save(book);

            final AddressBook loaded = addressBookRepository.getByAccountId(300L);
            loaded.remove(first.getId());
            addressBookRepository.save(loaded);

            assertThat(addressJpaRepository.findByBookIdOrderByIdAsc(book.getId())).isEmpty();
            assertThat(addressBookJpaRepository.existsById(book.getId())).isTrue();
    
        });
}

    /**
     * 删除簿：deleteByID 级联删子表+主表，返回真实删除条数（1=根行删除）。
     * 删除是写路径，事务边界由用例层持有（此处以编程式事务模拟）。
     */
    @Test
    @DisplayName("deleteByID 级联删除主表与子表并返回真实条数")
    void deleteByID_cascadesAndReturnsCount() {
        TrackingContext.withScope(() -> {
            final AddressBook book = addressBookFactory.createBook(400L);
            book.add(addressBookFactory.createAddress(book, "张三", "13800138000",
                    "浙江省", "杭州市", "西湖区", "文一西路 100 号", true));
            addressBookRepository.save(book);

            final Integer deleted = tx.execute(status -> addressBookRepository.deleteByID(book.getId()));
            assertThat(deleted.intValue()).isEqualTo(1);
            assertThat(addressBookJpaRepository.existsById(book.getId())).isFalse();
            assertThat(addressJpaRepository.findByBookIdOrderByIdAsc(book.getId())).isEmpty();

            assertThat(tx.execute(status -> addressBookRepository.deleteByID(book.getId())).intValue()).isZero();
    
        });
}

    /**
     * 空簿契约：从未建簿的买家读到空簿（聚合始终存在语义），save 空簿不产生根行。
     */
    @Test
    @DisplayName("无簿买家读到空簿且空簿 save 不落库")
    void getByAccountId_emptyBookContract() {
        TrackingContext.withScope(() -> {
            final AddressBook book = addressBookRepository.getByAccountId(500L);
            assertThat(book.getAccountId()).isEqualTo(500L);
            assertThat(book.snapshot()).isEmpty();

            addressBookRepository.save(book);
            assertThat(addressBookJpaRepository.findByAccountId(500L)).isEmpty();
    
        });
}
}