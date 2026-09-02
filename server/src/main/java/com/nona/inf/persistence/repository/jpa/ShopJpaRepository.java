package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.catalog.ShopPO;
import org.springframework.data.repository.ListCrudRepository;

/**
 * 店铺主表 JPA 仓储（shop 表，聚合根根行；id = 店铺 ID，global 无租户列）。
 *
 * @author nona9961
 */
public interface ShopJpaRepository extends ListCrudRepository<ShopPO, Long> {
}