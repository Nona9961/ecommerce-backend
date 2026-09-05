package com.nona.domain.order.repo;

import com.nona.domain.order.entity.Cart;
import com.nona.persistence.BaseRepository;

/**
 * 购物车仓储接口：买家购物车的持久化契约（cart 主表 + cart_item 从表，
 * 买家维度 global）。
 * <p>
 * 实现在基础设施层（继承 DifferRepository：主表快照追踪 + 变更集驱动
 * 落库——从表条目新增插行/删除删行/字段变更整行更新）；领域层只依赖本
 * 契约，不感知 JPA。聚合根主表以独立主键（cartId）承载购物车身份，
 * buyer_id 为业务关联列（一个买家一个购物车）；条目行以 cart_id（rootId）
 * 关联聚合根并冗余 buyer_id 承载 (buyer,sku) 唯一约束。
 *
 * @author nona9961
 */
public interface CartRepository extends BaseRepository<Long, Cart> {

    /**
     * 按买家账号加载购物车：无购物车时返回空车（聚合始终存在，不返回
     * null——首条加购前即有空车实体承载）。
     *
     * @param buyerId 买家账号 ID
     * @return 购物车（可能为空）
     */
    Cart getByBuyerId(Long buyerId);

    /**
     * 保存购物车：变更集驱动落库，条目新增/更新/删除最小化写库。
     *
     * @param cart 购物车
     * @return 是否有行级变更被持久化
     */
    @Override
    boolean save(Cart cart);

    /**
     * 买家维度悲观锁：对账号行加写锁，串行化同一买家的购物车写操作。
     * <p>
     * 同买家并发加购/改量/移除/勾选共享同一聚合（一个买家一个购物车），
     * 无锁时多个写事务基于各自读到的快照做变更集落库存在丢失更新窗口
     * （如并发累加同一 SKU 各写各的）；以账号行锁串行化同买家全部购物车
     * 写事务，聚合装载 → 变更 → 落库在锁内完成，两笔并发加购最终收敛
     * 为恰一条目且数量正确合并。(buyer,sku) 唯一约束兜底。锁由调用方
     * 事务持有至提交（调用方需处于活动事务中）。
     *
     * @param buyerId 买家账号 ID
     */
    void lockBuyer(Long buyerId);
}