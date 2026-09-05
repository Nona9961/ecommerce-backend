package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.identity.AccountPO;
import com.nona.inf.persistence.po.order.CartItemPO;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 购物车条目 JPA 仓储（cart_item 表，购物车从表行集合）。
 *
 * @author nona9961
 */
public interface CartItemJpaRepository extends ListCrudRepository<CartItemPO, Long> {

    /**
     * 按所属购物车查询全部条目（按 ID 升序，保持加购序）。
     *
     * @param cartId 购物车主键
     * @return 条目列表；无条目返回空列表
     */
    List<CartItemPO> findByCartIdOrderByIdAsc(Long cartId);

    /**
     * 按所属购物车删除全部条目（deleteByID 级联删除用；假删除返回 0，真删除返回行数）。
     *
     * @param cartId 购物车主键
     * @return 删除的行数
     */
    long deleteByCartId(Long cartId);

    /**
     * 买家维度写锁：对账号行加悲观写锁（for update），串行化同一买家的
     * 购物车写事务。
     * <p>
     * 查询借道账号表行（买家必然存在，登录链路保证）——购物车为空车
     * （无行可锁）时仍能提供稳定的买家级串行化点，是同买家并发加购/
     * 改量/移除/勾选的最终防线（聚合装载 → 变更 → 落库在锁内完成）。
     *
     * @param buyerId 买家账号 ID
     * @return 账号行（不存在返回空；正常路径恒存在）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountPO a where a.id = :buyerId")
    Optional<AccountPO> lockBuyerAccount(@Param("buyerId") Long buyerId);
}