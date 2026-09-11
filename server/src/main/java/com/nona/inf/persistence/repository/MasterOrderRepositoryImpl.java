package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.inf.persistence.converters.MasterOrderConvertor;
import com.nona.inf.persistence.po.order.MasterOrderPO;
import com.nona.inf.persistence.po.order.SubOrderPO;
import com.nona.inf.persistence.repository.jpa.MasterOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.SubOrderJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * 主订单仓储落地：继承 {@link DifferRepository}（主表快照 + 变更追踪），
 * 主表 master_order（global——无租户列，买家维度；id=独立 Snowflake
 * 主键 + order_no 唯一业务单号 + buyer_id 业务关联列），无直接从表
 * （子单为独立聚合根，经 sub_order.master_order_id 反查子单 id 集合为
 * 引用 ID 协作——主单不加载子单对象）。
 * <p>
 * 读：getOther 经子单主表 JPA 按 master_order_id 反查子单 id 集合
 * （引用 ID 协作，保持创建序）；装载语义冻结——主单 global 直读不受
 * 租户过滤，子单反查被请求上下文租户过滤（fail-closed：跨店反查空）。
 * 买家上下文（平台租户）下反查面为空集的消费语义（买家订单详情
 * 装载）由消费编排（详情读取面）以 elevated/read-bypass 装配
 * 处理——本类只冻结机械反查行为，不承载上下文豁免。分页列表行未
 * 登记变更追踪（只读呈现面）。
 * <p>
 * 查询契约（冻结，接口 javadoc 权威）：列表分页按买家 + 状态
 * 多值过滤（null/空 = 全量）+ 创建时间倒序 + 主键倒序 tie-breaker；
 * 参数守卫 offset&lt;0 或 limit&lt;=0 拒绝。排序/过滤/守卫语义见接口
 * javadoc，本类按契约机械装配。
 *
 * @author nona9961
 */
@Component
public class MasterOrderRepositoryImpl
        extends DifferRepository<MasterOrder, MasterOrderPO, List<Long>>
        implements MasterOrderRepository {

    /**
     * 子单主表 JPA 仓储（getOther 反查子单 id 集合消费面）
     */
    private final SubOrderJpaRepository subOrderJpaRepository;

    /**
     * 主表 JPA 仓储（买家分页/业务关联查询消费面——父类 repository
     * 字段为泛型契约类型，本类显式持有具体面）
     */
    private final MasterOrderJpaRepository jpaRepository;

    /**
     * 构造主订单仓储。
     *
     * @param repository            主订单主表 JPA 仓储
     * @param convertor             主订单聚合转换器（主表行 + 子单 id 集合）
     * @param changeTrackerProvider 变更追踪器提供者
     * @param subOrderJpaRepository 子单主表 JPA 仓储（反查消费面）
     */
    public MasterOrderRepositoryImpl(MasterOrderJpaRepository repository,
                                     MasterOrderConvertor convertor,
                                     ChangeTrackerProvider changeTrackerProvider,
                                     SubOrderJpaRepository subOrderJpaRepository) {
        super(repository, convertor, changeTrackerProvider);
        this.subOrderJpaRepository = subOrderJpaRepository;
        this.jpaRepository = repository;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 子单 id 集合按 sub_order.master_order_id 反查（引用 ID 协作，
     * 保持创建序——与聚合 javadoc「子单引用集合创建时定型」对应）。
     */
    @Override
    protected List<Long> getOther(MasterOrderPO po) {
        return subOrderJpaRepository.findByMasterOrderIdOrderByIdAsc(po.getId())
                .stream().map(SubOrderPO::getId).toList();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 聚合根主键 = 主订单独立主键（masterOrderId）。
     */
    @Override
    protected Long retrieveIDFromRoot(MasterOrder root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 插入根行（主表唯一行；子单为独立聚合根不经本路径落库）。
     */
    @Override
    protected void doInsert(MasterOrder root) {
        jpaRepository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 单表聚合仅有根行字段变更（整体状态派生刷新）：整行更新主表
     * （JPA merge 语义，避免逐字段映射漂移）。
     */
    @Override
    protected void doUpdate(MasterOrder root, ChangeSet changeSet) {
        jpaRepository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除主订单：委托 {@link #deleteByID}。
     */
    @Override
    public int delete(MasterOrder masterOrder) {
        return masterOrder == null ? 0 : deleteByID(masterOrder.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除主表行并返回真实删除条数（0/1；主单无直接从表，子单为独立
     * 聚合根不级联删除——跨聚合引用 ID 协作，主单删除不波及子单行）。
     * 事务边界由用例层持有。
     */
    @Override
    public int deleteByID(Long masterOrderId) {
        if (masterOrderId == null || !jpaRepository.existsById(masterOrderId)) {
            return 0;
        }
        jpaRepository.deleteById(masterOrderId);
        return 1;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 买家订单分页：状态多值过滤（null/空集合 = 全量走无过滤分支）+
     * 创建时间倒序 + 主键倒序；参数守卫 offset&lt;0/limit&lt;=0 拒绝。
     */
    @Override
    public List<MasterOrder> listPagedByBuyer(Long buyerId,
                                              Collection<MasterOrderStatus> statuses,
                                              int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能为负：" + offset);
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正：" + limit);
        }
        final PageRequest pageRequest = PageRequest.of(offset / limit, limit);
        final Page<MasterOrderPO> page = (statuses == null || statuses.isEmpty())
                ? jpaRepository.findByBuyerIdOrderByCreateTimeDescIdDesc(buyerId, pageRequest)
                : jpaRepository.findByBuyerIdAndStatusInOrderByCreateTimeDescIdDesc(
                        buyerId, statuses, pageRequest);
        return page.getContent().stream()
                .map(po -> convertor.convertToRoot(po, getOther(po)))
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countByBuyer(Long buyerId, Collection<MasterOrderStatus> statuses) {
        return (statuses == null || statuses.isEmpty())
                ? jpaRepository.countByBuyerId(buyerId)
                : jpaRepository.countByBuyerIdAndStatusIn(buyerId, statuses);
    }
}