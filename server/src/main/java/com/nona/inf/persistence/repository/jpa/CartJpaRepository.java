package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.order.CartPO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;

/**
 * 购物车主表 JPA 仓储（cart 表，购物车主表行）。
 *
 * @author nona9961
 */
public interface CartJpaRepository extends ListCrudRepository<CartPO, Long> {

    /**
     * 按买家账号查询购物车主表行（一个买家一个购物车）。
     *
     * @param buyerId 买家账号 ID
     * @return 购物车主表行；不存在返回空
     */
    Optional<CartPO> findByBuyerId(Long buyerId);
}