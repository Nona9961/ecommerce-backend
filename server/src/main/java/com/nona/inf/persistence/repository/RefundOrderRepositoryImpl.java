package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.nona.domain.payment.entity.RefundCallbackRecord;
import com.nona.domain.payment.entity.RefundOrder;
import com.nona.domain.payment.repo.RefundOrderRepository;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.converters.RefundCallbackRecordConvertor;
import com.nona.inf.persistence.converters.RefundOrderConvertor;
import com.nona.inf.persistence.po.payment.RefundCallbackLogPO;
import com.nona.inf.persistence.po.payment.RefundOrderPO;
import com.nona.inf.persistence.repository.jpa.RefundCallbackLogJpaRepository;
import com.nona.inf.persistence.repository.jpa.RefundOrderJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 退款单仓储落地：继承 {@link DifferRepository}（主表快照 + 变更追踪），
 * 主表 refund_order（global）+ refund_callback_log 从表（rootId 关联，
 * append-only 留痕，加载序 ID 升序——PaymentOrder/payment_callback_log
 * 判例同构）。
 * <p>
 * 读：主键装载走基类模板（getOther 经从表 JPA 按 refundOrderId 反查
 * 留痕行集合，聚合内存装配）；按退款单号/按子单装载为回调处理与申请
 * 防重的装载锚点（接口 javadoc 冻结：不存在返回 null——孤儿回调不
 * 产生处理路径、防重按「新建」处理）。写：唯一约束契约（refund_no /
 * sub_order_id 唯一；channel_refund_txn_no 不建唯一——FAILED 重试覆盖
 * 更新流水由聚合方法守卫承载）由表级约束与聚合方法共同承载，本类只
 * 做变更集驱动落库（留痕追加插行同支付单判例）。
 * <p>
 * 删除：deleteByID 级联删从表留痕（按退款单主键——从表行加载后整批
 * 删除，不新增派生删除方法）+ 主表行后删，返回真实删除条数（0/1）。
 * 本聚合无超时 SQL 面（退款单不参与超时扫描）。
 *
 * @author nona9961
 */
@Component
public class RefundOrderRepositoryImpl
        extends DifferRepository<RefundOrder, RefundOrderPO, List<RefundCallbackLogPO>>
        implements RefundOrderRepository {

    /**
     * 留痕集合在聚合中的字段名（变更集路径解析用）
     */
    private static final String CALLBACKS_FIELD = "callbacks";

    /**
     * 退款回调留痕从表 JPA 仓储（getOther 装载/级联删消费面）
     */
    private final RefundCallbackLogJpaRepository callbackLogJpaRepository;

    /**
     * 主表 JPA 仓储（业务关联装载——父类 repository 字段为泛型契约
     * 类型，本类显式持有具体面）
     */
    private final RefundOrderJpaRepository jpaRepository;

    /**
     * 留痕行转换器（从表落库消费面；构造器签名不含本
     * 依赖——单测不触达落库路径，以字段注入补齐装配面）
     */
    @Autowired
    private RefundCallbackRecordConvertor callbackConvertor;

    /**
     * 构造退款单仓储。
     *
     * @param repository               退款单主表 JPA 仓储
     * @param convertor                退款单聚合转换器（主表行 + 留痕集合）
     * @param changeTrackerProvider    变更追踪器提供者
     * @param callbackLogJpaRepository 退款回调留痕从表 JPA 仓储
     */
    public RefundOrderRepositoryImpl(RefundOrderJpaRepository repository,
                                     RefundOrderConvertor convertor,
                                     ChangeTrackerProvider changeTrackerProvider,
                                     RefundCallbackLogJpaRepository callbackLogJpaRepository) {
        super(repository, convertor, changeTrackerProvider);
        this.callbackLogJpaRepository = callbackLogJpaRepository;
        this.jpaRepository = repository;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 留痕行按加载序读出（ID 升序），作为聚合恢复的 other 输入。
     */
    @Override
    protected List<RefundCallbackLogPO> getOther(RefundOrderPO po) {
        return callbackLogJpaRepository.findByRefundOrderIdOrderByIdAsc(po.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 聚合根主键 = 退款单独立主键（refundOrderId）。
     */
    @Override
    protected Long retrieveIDFromRoot(RefundOrder root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 插入根行（退款单首次落库；留痕行经退款回调用例追加路径插行）。
     */
    @Override
    protected void doInsert(RefundOrder root) {
        jpaRepository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 变更集驱动落库：根行字段变更（状态/渠道流水号推进）整行更新
     * （JPA merge 语义）+ 留痕追加插行（append 追加 → ItemAddedChange
     * → 从表 insert，支付单判例同构）。
     */
    @Override
    protected void doUpdate(RefundOrder root, ChangeSet changeSet) {
        boolean rootChanged = false;
        for (final Change change : changeSet.getLeafChanges()) {
            final String collectionField = change.collectionFieldName();
            if (collectionField == null) {
                rootChanged = true;
                continue;
            }
            if (!CALLBACKS_FIELD.equals(collectionField)) {
                continue;
            }
            if (change instanceof ItemAddedChange added) {
                final Long recordId = extractIdentifier(added.addedItem());
                findRecord(root, recordId)
                        .ifPresent(record -> callbackLogJpaRepository.save(
                                callbackConvertor.toPO(record)));
            } else if (change instanceof ItemRemovedChange removed) {
                final Long recordId = extractIdentifier(removed.removedItem());
                if (recordId != null) {
                    callbackLogJpaRepository.deleteById(recordId);
                }
            } else {
                // ValueChange / ObjectFieldChange：整行更新，避免逐字段映射漂移
                saveChangedRecord(root, change);
            }
        }
        if (rootChanged) {
            jpaRepository.save(convertor.convertToPO(root));
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 回调装载面（回调处理锚点）：按退款单号装载主表行 + 留痕集合；
     * <b>登记变更追踪快照基线</b>（getByID 路径同款登记）——装载实例后
     * 续 save（回调迁移 + 留痕追加）走变更集驱动 doUpdate；不登记则模板
     * 按未追踪视作新增走 doInsert（JPA merge 根行更新但跳过从表变更
     * 驱动——留痕行丢失）。
     */
    @Override
    public RefundOrder findByRefundNo(String refundNo) {
        return jpaRepository.findByRefundNo(refundNo)
                .map(po -> {
                    final RefundOrder root = convertor.convertToRoot(po, getOther(po));
                    getOrCreateChangeTracker().track(root);
                    TrackingContext.scope().getSnapshots().put(root.getId(), root);
                    return root;
                })
                .orElse(null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 防重装载面（申请/超时编排锚点）：按子单装载主表行 + 留痕集合；
     * 快照基线登记同 {@link #findByRefundNo}（装载实例后续 save 走变更
     * 集驱动，杜绝 doInsert 跳过从表变更）。
     */
    @Override
    public RefundOrder findBySubOrderId(Long subOrderId) {
        return jpaRepository.findBySubOrderId(subOrderId)
                .map(po -> {
                    final RefundOrder root = convertor.convertToRoot(po, getOther(po));
                    getOrCreateChangeTracker().track(root);
                    TrackingContext.scope().getSnapshots().put(root.getId(), root);
                    return root;
                })
                .orElse(null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除退款单：委托 {@link #deleteByID}。
     */
    @Override
    public int delete(RefundOrder refundOrder) {
        return refundOrder == null ? 0 : deleteByID(refundOrder.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 级联删除：从表留痕行按退款单主键整批删除（加载集合 →
     * deleteAll），再删根行；返回根行删除条数（0/1 真实语义，非
     * 契约形）。事务边界由用例层持有。
     */
    @Override
    public int deleteByID(Long refundOrderId) {
        if (refundOrderId == null || !jpaRepository.existsById(refundOrderId)) {
            return 0;
        }
        final List<RefundCallbackLogPO> logs =
                callbackLogJpaRepository.findByRefundOrderIdOrderByIdAsc(refundOrderId);
        if (!logs.isEmpty()) {
            callbackLogJpaRepository.deleteAll(logs);
        }
        jpaRepository.deleteById(refundOrderId);
        return 1;
    }

    /**
     * 从变更节点提取集合项标识（Identifier 提取器按实体 id 注册）。
     *
     * @param node 变更节点（ObjectNode）
     * @return 集合项 ID；无法解析返回 null
     */
    private static Long extractIdentifier(ValueNode node) {
        if (node instanceof ObjectNode objectNode && objectNode.identifier() instanceof Long id) {
            return id;
        }
        return null;
    }

    /**
     * 从聚合留痕集合中按记录 ID 定位实体（整行更新/新增落库前取最新态）。
     *
     * @param root     退款单聚合
     * @param recordId 留痕记录 ID
     * @return 留痕记录；不存在返回空
     */
    private static Optional<RefundCallbackRecord> findRecord(RefundOrder root, Long recordId) {
        return root.getCallbacks().stream()
                .filter(record -> recordId != null && recordId.equals(record.getId()))
                .findFirst();
    }

    /**
     * 从 root 中按变更路径里的集合项 ID 找到留痕，整行更新。
     *
     * @param root   退款单聚合
     * @param change 字段变更（path 形如 callbacks[&lt;id&gt;].field）
     */
    private void saveChangedRecord(RefundOrder root, Change change) {
        final Long recordId = extractIdFromPath(change.path());
        if (recordId != null) {
            findRecord(root, recordId)
                    .ifPresent(record -> callbackLogJpaRepository.save(
                            callbackConvertor.toPO(record)));
        }
    }

    /**
     * 从变更路径提取集合项 ID（path 形如 callbacks[&lt;id&gt;].field）。
     *
     * @param path 变更路径
     * @return 集合项 ID；无法解析返回 null
     */
    private static Long extractIdFromPath(String path) {
        final int start = path.indexOf('[');
        final int end = path.indexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return Long.parseLong(path.substring(start + 1, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}