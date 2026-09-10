package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.converters.OrderItemConvertor;
import com.nona.inf.persistence.converters.SubOrderConvertor;
import com.nona.inf.persistence.po.TenantScopedBasePO;
import com.nona.inf.persistence.po.order.OrderItemPO;
import com.nona.inf.persistence.po.order.SubOrderPO;
import com.nona.inf.persistence.repository.jpa.OrderItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.SubOrderJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import com.nona.util.IDUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

/**
 * 子订单仓储落地：继承 {@link DifferRepository}（主表快照 + 变更追踪），
 * 主表 sub_order（tenant=shopId——租户过滤 fail-closed 隔离）+ order_item
 * 从表（sub_order_id=rootId 关联，加载序 ID 升序）。
 * <p>
 * 读：主键装载走基类模板（getOther 经从表 JPA 按 sub_order_id 反查
 * 条目集合——条目集合随聚合内存装配）；主单维度反查（getByMasterOrderId）
 * 与超时扫描面（findDue）为查询面——反查装载语义冻结在接口 javadoc；
 * 分页列表行未登记变更追踪。写：租户列由写门禁按请求上下文注入
 * （商家请求 tenant=当前店铺）；提权写路径（买家/回调/调度等无请求
 * 视角上下文推进店铺数据）经 {@link #ownedBy} 显式锚定 tenant=shopId
 * （TenantWriteGate 提权+空归属 fail-closed 拒绝，商品域仓储同先例
 * 形态），读经租户过滤 fail-closed。
 * <p>
 * <b>超时 SQL 三件套（WU-55 冻结落地面）</b>——PO 超时 SQL 面三列
 * （timeout_at/timeout_type/claimed）由本仓储维护，转换器不负责
 * （WU-54 契约）：
 * <ul>
 *     <li>findDue：走 JPA 派生扫描面 {@code findByStatusAndTimeoutAtLessThanEqual...}，
 *         领域 Instant 以 UTC 字面转 PO LocalDateTime（ZoneOffset.UTC，
 *         WU-54 转换器同款形态），状态值由调用方（履约超时数据端口）
 *         传入，本类不写死；</li>
 *     <li>claim/clear：JdbcTemplate 参数化条件 UPDATE（InventoryItem
 *         casX 的 JPA @Modifying 先例不适用——引擎调度线程无请求租户
 *         上下文，条件更新按「主键全局唯一」定位（Snowflake 主键跨
 *         店无碰撞），不注入租户条件；参数化语句符合数据库纪律禁止
 *         拼接条款）。claim = {@code UPDATE sub_order SET claimed = 1
 *         WHERE id = ? AND claimed = 0 AND status = ?} 受影响 1 行 →
 *         true；clear = {@code UPDATE sub_order SET timeout_at = NULL,
 *         timeout_type = NULL, claimed = 0 WHERE id = ?} 无条件幂等。</li>
 * </ul>
 * 删除：deleteByID 级联删从表（order_item 先删）+ 主表行后删，返回
 * 真实删除条数（0/1 根行）。
 *
 * @author nona9961
 */
@Component
public class SubOrderRepositoryImpl
        extends DifferRepository<SubOrder, SubOrderPO, List<OrderItemPO>>
        implements SubOrderRepository {

    /**
     * 超时认领条件更新（全局唯一主键定位；引擎线程无租户上下文）
     */
    private static final String CLAIM_TIMEOUT_SQL =
            "UPDATE sub_order SET claimed = 1 WHERE id = ? AND claimed = 0 AND status = ?";

    /**
     * 超时截止清除（无条件幂等）
     */
    private static final String CLEAR_TIMEOUT_SQL =
            "UPDATE sub_order SET timeout_at = NULL, timeout_type = NULL, claimed = 0 WHERE id = ?";

    /**
     * 订单项从表 JPA 仓储（getOther 装载/级联删/变更集落库消费面）
     */
    private final OrderItemJpaRepository orderItemJpaRepository;

    /**
     * 主表 JPA 仓储（业务关联查询/分页面——父类 repository 字段为
     * 泛型契约类型，本类显式持有具体面）
     */
    private final SubOrderJpaRepository jpaRepository;

    /**
     * 超时条件更新落地面（claim/clear 参数化 SQL）
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 订单项行转换器（从表落库消费面；红阶段冻结的构造器签名不含
     * 本依赖——单测不触达落库路径，以字段注入补齐装配面）
     */
    @Autowired
    private OrderItemConvertor orderItemConvertor;

    /**
     * 提权工具（提权保存前显式租户归属定型：TenantWriteGate 提权+空归属
     * fail-closed 拒绝——子单/订单项行 tenant=shopId 归属必得，不依赖请求
     * 上下文；红阶段冻结的构造器签名不含本依赖——单测不触达落库路径，以
     * 字段注入补齐装配面，同 orderItemConvertor 形态）。
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 构造子订单仓储。
     *
     * @param repository            子订单主表 JPA 仓储
     * @param convertor             子订单聚合转换器（主表行 + 条目集合）
     * @param changeTrackerProvider 变更追踪器提供者
     * @param orderItemJpaRepository 订单项从表 JPA 仓储
     * @param jdbcTemplate          超时条件更新落地面
     */
    public SubOrderRepositoryImpl(SubOrderJpaRepository repository,
                                  SubOrderConvertor convertor,
                                  ChangeTrackerProvider changeTrackerProvider,
                                  OrderItemJpaRepository orderItemJpaRepository,
                                  JdbcTemplate jdbcTemplate) {
        super(repository, convertor, changeTrackerProvider);
        this.orderItemJpaRepository = orderItemJpaRepository;
        this.jpaRepository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从表条目按加载序读出（ID 升序），作为聚合恢复的 other 输入。
     */
    @Override
    protected List<OrderItemPO> getOther(SubOrderPO po) {
        return orderItemJpaRepository.findBySubOrderIdOrderByIdAsc(po.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 聚合根主键 = 子订单独立主键（subOrderId）。
     */
    @Override
    protected Long retrieveIDFromRoot(SubOrder root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 插入根行 + 全部从表条目行（新子单首次落库；条目行 id 由落库
     * 路径生成）。
     */
    @Override
    protected void doInsert(SubOrder root) {
        jpaRepository.save(ownedBy(convertor.convertToPO(root), root));
        for (final OrderItem item : root.getItems()) {
            insertItemRow(root, item);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 根行字段变更（状态/金额等）整行更新（JPA merge 语义，避免逐
     * 字段映射漂移）；条目集合（{@link OrderItem} 快照）在聚合内定型
     * 后不可变（无增删改路径）——从表行仅创建期经 {@link #doInsert}
     * 一次性落库，变更集不含条目集合路径面，doUpdate 不触碰从表。
     */
    @Override
    protected void doUpdate(SubOrder root, ChangeSet changeSet) {
        final boolean rootChanged = changeSet.getLeafChanges().stream()
                .anyMatch(change -> change.collectionFieldName() == null);
        if (rootChanged || !jpaRepository.existsById(root.getId())) {
            jpaRepository.save(ownedBy(convertor.convertToPO(root), root));
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 主单维度反查：经从属主单装载子单集合（保持创建序；每行经转换器
     * 装配从表条目）。
     * <p>
     * <b>快照基线登记（变更追踪模板语义）</b>：本面装载的每行聚合登记
     * 变更追踪快照基线（getByID 路径同款登记）——同批保存消费面
     * （OrderFacadeImpl 列表保存面）以本面装载实例参与 {\@code save} 时，
     * 未修改实例变更集空（isEmpty 提前跳过）、已修改实例变更集驱动
     * {\@link #doUpdate}；不登记则模板按未追踪视作新增走
     * {\@link #doInsert} 重复插入（uk_order_item_sub_sku 等唯一约束冲突）。
     * 只读消费面（查询/明细装配）登记无副作用（快照按根隔离，不参与
     * 其他根变更计算）。
     */
    @Override
    public List<SubOrder> getByMasterOrderId(Long masterOrderId) {
        return jpaRepository.findByMasterOrderIdOrderByIdAsc(masterOrderId).stream()
                .map(po -> {
                    final SubOrder root = convertor.convertToRoot(po, getOther(po));
                    getOrCreateChangeTracker().track(root);
                    TrackingContext.scope().getSnapshots().put(root.getId(), root);
                    return root;
                })
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<SubOrder> findDueByStatusAndTimeoutAtBefore(SubOrderStatus status,
                                                            Instant now, int limit) {
        return jpaRepository.findByStatusAndTimeoutAtLessThanEqualOrderByTimeoutAtAsc(
                        status, LocalDateTime.ofInstant(now, ZoneOffset.UTC), Limit.of(limit))
                .stream()
                .map(po -> convertor.convertToRoot(po, getOther(po)))
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean claimTimeout(Long id, SubOrderStatus expectedStatus) {
        return jdbcTemplate.update(CLAIM_TIMEOUT_SQL, id, expectedStatus.name()) == 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearTimeoutDeadline(Long id) {
        jdbcTemplate.update(CLEAR_TIMEOUT_SQL, id);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 店铺订单分页：shop_id 显式条件 + 租户过滤双层 + 状态多值过滤
     * （null/空 = 全量）+ 创建时间倒序 + 主键倒序；参数守卫
     * offset&lt;0/limit&lt;=0 拒绝。
     */
    @Override
    public List<SubOrder> listPagedByShop(Long shopId,
                                          Collection<SubOrderStatus> statuses,
                                          int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能为负：" + offset);
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正：" + limit);
        }
        final PageRequest pageRequest = PageRequest.of(offset / limit, limit);
        final Page<SubOrderPO> page = (statuses == null || statuses.isEmpty())
                ? jpaRepository.findByShopIdOrderByCreateTimeDescIdDesc(shopId, pageRequest)
                : jpaRepository.findByShopIdAndStatusInOrderByCreateTimeDescIdDesc(
                        shopId, statuses, pageRequest);
        return page.getContent().stream()
                .map(po -> convertor.convertToRoot(po, getOther(po)))
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countByShop(Long shopId, Collection<SubOrderStatus> statuses) {
        return (statuses == null || statuses.isEmpty())
                ? jpaRepository.countByShopId(shopId)
                : jpaRepository.countByShopIdAndStatusIn(shopId, statuses);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除整单：委托 {@link #deleteByID}（按子单主键级联删除）。
     */
    @Override
    public int delete(SubOrder subOrder) {
        return subOrder == null ? 0 : deleteByID(subOrder.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 级联删除：先删从表条目行（按 sub_order_id），再删根行；返回
     * 根行删除条数（0/1 真实语义，非契约形）。事务边界由用例层持有。
     */
    @Override
    public int deleteByID(Long subOrderId) {
        if (subOrderId == null || !jpaRepository.existsById(subOrderId)) {
            return 0;
        }
        orderItemJpaRepository.deleteBySubOrderId(subOrderId);
        jpaRepository.deleteById(subOrderId);
        return 1;
    }

    /**
     * 条目行落库：行 id 由落库路径生成（OrderItem 为无标识快照实体），
     * 归属子单关联列按根行定型。
     *
     * @param root 子订单聚合
     * @param item 条目（最新态）
     */
    private void insertItemRow(SubOrder root, OrderItem item) {
        final OrderItemPO po = orderItemConvertor.toPO(item);
        po.setId(IDUtils.generateID());
        po.setSubOrderId(root.getId());
        orderItemJpaRepository.save(ownedBy(po, root));
    }

    /**
     * 根行/从表行租户承载：提权写路径（买家/回调/调度等无请求视角上下文
     * 推进店铺数据）显式锚定 tenant=shopId——归属必得，不依赖请求上下文
     * （TD-12 提权写门禁语义：TenantWriteGate 提权+空归属 fail-closed 拒绝）；
     * 非提权商家路径保持既有注入语义（行租户由写门禁按请求上下文注入，
     * 显式值缺失即放行注入）。商品域仓储 ownedBy 同先例形态。
     *
     * @param po   行 PO
     * @param root 子订单聚合根（租户锚点）
     * @param <T>  行 PO 类型
     * @return 承载租户后的 PO
     */
    private <T extends TenantScopedBasePO> T ownedBy(T po, SubOrder root) {
        if (tenantPrivilege.isActive()) {
            po.setTenantID(String.valueOf(root.getShopId()));
        }
        return po;
    }

}