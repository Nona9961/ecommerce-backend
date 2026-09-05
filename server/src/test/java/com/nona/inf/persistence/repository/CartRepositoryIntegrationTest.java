package com.nona.inf.persistence.repository;

import com.nona.domain.order.entity.Cart;
import com.nona.domain.order.entity.CartItem;
import com.nona.domain.order.factory.CartFactory;
import com.nona.domain.order.repo.CartRepository;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.repository.jpa.CartItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.CartJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 购物车仓储集成测试：聚合根主表（cart）存在性、变更集驱动落库（新增
 * 插行/删除删行/字段变更更新）、删除语义（主表+子表级联）、空车加载
 * 契约（聚合恒存在）。
 * <p>
 * 与 DDD 红线对应：聚合根有根表（cart 一行=一车，主键=购物车独立主键
 * cartId，buyer_id 业务关联唯一）；仓储继承 DifferRepository，读=track
 * 快照、save=变更集落库；从表以 cart_id（rootId）关联并冗余 buyer_id
 * 承载 (buyer,sku) 唯一约束。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class CartRepositoryIntegrationTest {

    /**
     * 购物车仓储（被测对象：DifferRepository 子类）
     */
    @Autowired
    private CartRepository cartRepository;

    /**
     * 购物车主表 JPA（断言根表行）
     */
    @Autowired
    private CartJpaRepository cartJpaRepository;

    /**
     * 购物车条目 JPA（断言子表行）
     */
    @Autowired
    private CartItemJpaRepository cartItemJpaRepository;

    /**
     * 购物车聚合工厂（创建车与条目）
     */
    @Autowired
    private CartFactory cartFactory;

    /**
     * 编程式事务模板（删除是写路径，需在事务内——模拟用例层事务边界）
     */
    @Autowired
    private TransactionTemplate tx;

    /**
     * 每用例前清空主表+子表（根表先行，避免残留干扰）。
     */
    @BeforeEach
    void setUp() {
        cartItemJpaRepository.deleteAll();
        cartJpaRepository.deleteAll();
    }

    /**
     * 新增：新购物车首次落库（doInsert 根行+子行），主表一车一行，
     * 条目可整体读回。
     */
    @Test
    @DisplayName("新购物车首次落库根行与子表行齐备")
    void save_insertsRootRowAndChildRows() {
        TrackingContext.withScope(() -> {
            final Cart cart = cartFactory.createCart(10001L);
            cart.add(cartFactory.createItem(cart, 2001L, 3001L, 2, true, 4001L, "店铺甲"), 10);
            cartRepository.save(cart);

            assertThat(cartJpaRepository.existsById(cart.getId())).isTrue();
            assertThat(cartJpaRepository.findByBuyerId(10001L)).isPresent();
            assertThat(cartItemJpaRepository.findByCartIdOrderByIdAsc(cart.getId())).hasSize(1);

            final Cart loaded = cartRepository.getByBuyerId(10001L);
            assertThat(loaded.getId()).isEqualTo(cart.getId());
            assertThat(loaded.getBuyerId()).isEqualTo(10001L);
            assertThat(loaded.snapshot()).hasSize(1);
            final CartItem entry = loaded.getBySkuId(3001L).orElseThrow();
            assertThat(entry.getQuantity()).isEqualTo(2);
            assertThat(entry.isChecked()).isTrue();
            assertThat(entry.getShopId()).isEqualTo(4001L);
        });
    }

    /**
     * 空车加购落库：从未建车的买家读到空车（track 快照）→ 聚合 add →
     * save 走变更集路径（ensure 根行 + 集合新增插行），根行与子行齐备。
     */
    @Test
    @DisplayName("空车加购后变更集路径落库根行与子表行")
    void addToEmptyCart_persistsRootAndChild() {
        TrackingContext.withScope(() -> {
            final Cart cart = cartRepository.getByBuyerId(10002L);
            assertThat(cart.snapshot()).isEmpty();

            cart.add(cartFactory.createItem(cart, 2001L, 3001L, 3, true, 4001L, "店铺甲"), 10);
            cartRepository.save(cart);

            assertThat(cartJpaRepository.findByBuyerId(10002L)).isPresent();
            assertThat(cartItemJpaRepository.findByCartIdOrderByIdAsc(cart.getId())).hasSize(1);

            final Cart reloaded = cartRepository.getByBuyerId(10002L);
            assertThat(reloaded.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(3);
        });
    }

    /**
     * 更新：重载建立快照基线后改量（绝对量），save 变更集整行更新——行数
     * 不变、字段更新；聚合可读回新量。
     */
    @Test
    @DisplayName("改量后变更集整行更新子表")
    void save_updateAppliesChangedRow() {
        TrackingContext.withScope(() -> {
            final Cart cart = cartFactory.createCart(10003L);
            cart.add(cartFactory.createItem(cart, 2001L, 3001L, 2, true, 4001L, "店铺甲"), 10);
            cart.add(cartFactory.createItem(cart, 2001L, 3002L, 4, false, 4001L, "店铺甲"), 10);
            cartRepository.save(cart);

            final Cart loaded = cartRepository.getByBuyerId(10003L);
            loaded.updateQuantity(3001L, 5, 10);
            cartRepository.save(loaded);

            final Cart reloaded = cartRepository.getByBuyerId(10003L);
            assertThat(reloaded.snapshot()).hasSize(2);
            assertThat(reloaded.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(5);
            assertThat(reloaded.getBySkuId(3002L).orElseThrow().getQuantity()).isEqualTo(4);
        });
    }

    /**
     * 删除条目：重载后聚合 remove，save 变更集删子表行；根表行保留
     * （购物车仍存在）。
     */
    @Test
    @DisplayName("移除条目后子表行删除、根表行保留")
    void save_removeChildKeepsRootRow() {
        TrackingContext.withScope(() -> {
            final Cart cart = cartFactory.createCart(10004L);
            cart.add(cartFactory.createItem(cart, 2001L, 3001L, 2, true, 4001L, "店铺甲"), 10);
            cartRepository.save(cart);

            final Cart loaded = cartRepository.getByBuyerId(10004L);
            loaded.remove(3001L);
            cartRepository.save(loaded);

            assertThat(cartItemJpaRepository.findByCartIdOrderByIdAsc(cart.getId())).isEmpty();
            assertThat(cartJpaRepository.existsById(cart.getId())).isTrue();
        });
    }

    /**
     * 删除车：deleteByID 级联删子表+主表，返回真实删除条数（1=根行删除；
     * 再删 0）。
     */
    @Test
    @DisplayName("deleteByID 级联删除主表与子表并返回真实条数")
    void deleteByID_cascadesAndReturnsCount() {
        TrackingContext.withScope(() -> {
            final Cart cart = cartFactory.createCart(10005L);
            cart.add(cartFactory.createItem(cart, 2001L, 3001L, 2, true, 4001L, "店铺甲"), 10);
            cartRepository.save(cart);

            final Integer deleted = tx.execute(status -> cartRepository.deleteByID(cart.getId()));
            assertThat(deleted.intValue()).isEqualTo(1);
            assertThat(cartJpaRepository.existsById(cart.getId())).isFalse();
            assertThat(cartItemJpaRepository.findByCartIdOrderByIdAsc(cart.getId())).isEmpty();

            assertThat(tx.execute(status -> cartRepository.deleteByID(cart.getId())).intValue()).isZero();
        });
    }

    /**
     * 空车契约：从未建车的买家读到空车（聚合恒存在语义），空车 save
     * 无变更集（changeSet 空）不落库。
     */
    @Test
    @DisplayName("无车买家读到空车且空车 save 不落库")
    void getByBuyerId_emptyCartContract() {
        TrackingContext.withScope(() -> {
            final Cart cart = cartRepository.getByBuyerId(10006L);
            assertThat(cart.getBuyerId()).isEqualTo(10006L);
            assertThat(cart.snapshot()).isEmpty();

            cartRepository.save(cart);
            assertThat(cartJpaRepository.findByBuyerId(10006L)).isEmpty();
        });
    }
}