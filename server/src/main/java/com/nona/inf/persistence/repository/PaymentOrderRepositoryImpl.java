package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.nona.domain.payment.entity.PaymentCallbackRecord;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.inf.persistence.converters.PaymentCallbackRecordConvertor;
import com.nona.inf.persistence.converters.PaymentOrderConvertor;
import com.nona.inf.persistence.po.payment.PaymentCallbackLogPO;
import com.nona.inf.persistence.po.payment.PaymentOrderPO;
import com.nona.inf.persistence.repository.jpa.PaymentCallbackLogJpaRepository;
import com.nona.inf.persistence.repository.jpa.PaymentOrderJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 支付单仓储落地：继承 {@link DifferRepository}（主表快照 + 变更追踪），
 * 主表 payment_order（global）+ payment_callback_log 从表（rootId
 * 关联，append-only 留痕，加载序 ID 升序——Cart/CartItem 判例同构）。
 * <p>
 * 读：主键装载走基类模板（getOther 经从表 JPA 按 paymentOrderId
 * 反查留痕行集合，聚合内存装配）；按支付单号/按主单装载为回调处理
 * 与发起支付复用判定的装载锚点（接口 javadoc 冻结：不存在返回
 * null，不产生孤儿处理路径）。写：唯一约束契约（pay_no / order_id /
 * channel_txn_no）由表级约束兜底，本类只做变更集驱动落库。
 * <p>
 * <b>超时 SQL 三件套（WU-55 冻结落地面，同 SubOrderRepositoryImpl
 * 设计）</b>：findDue 走 JPA 派生扫描面（领域 Instant UTC 字面转 PO
 * LocalDateTime）；claim/clear 用 JdbcTemplate 参数化条件 UPDATE——
 * claim = {@code UPDATE payment_order SET claimed = 1 WHERE id = ? AND
 * claimed = 0 AND status = ?}（受影响 1 行 → true）；clear =
 * {@code UPDATE payment_order SET timeout_at = NULL, timeout_type = NULL,
 * claimed = 0 WHERE id = ?}（无条件幂等）。引擎调度线程无请求租户
 * 上下文，条件更新按「主键全局唯一」定位，不注入租户条件（与库存
 * casX 业务面条件更新的差异依据见设计报告）。
 * <p>
 * 删除：deleteByID 级联删从表留痕（按支付单主键——从表行加载后整批
 * 删除，不新增派生删除方法）+ 主表行后删，返回真实删除条数（0/1）。
 *
 * @author nona9961
 */
@Component
public class PaymentOrderRepositoryImpl
        extends DifferRepository<PaymentOrder, PaymentOrderPO, List<PaymentCallbackLogPO>>
        implements PaymentOrderRepository {

    /**
     * 留痕集合在聚合中的字段名（变更集路径解析用）
     */
    private static final String CALLBACKS_FIELD = "callbacks";

    /**
     * 超时认领条件更新（已冻结落地面：全局唯一主键定位）
     */
    private static final String CLAIM_TIMEOUT_SQL =
            "UPDATE payment_order SET claimed = 1 WHERE id = ? AND claimed = 0 AND status = ?";

    /**
     * 超时截止清除（无条件幂等）
     */
    private static final String CLEAR_TIMEOUT_SQL =
            "UPDATE payment_order SET timeout_at = NULL, timeout_type = NULL, claimed = 0 WHERE id = ?";

    /**
     * 回调留痕从表 JPA 仓储（getOther 装载/级联删消费面）
     */
    private final PaymentCallbackLogJpaRepository callbackLogJpaRepository;

    /**
     * 主表 JPA 仓储（业务关联装载——父类 repository 字段为泛型契约
     * 类型，本类显式持有具体面）
     */
    private final PaymentOrderJpaRepository jpaRepository;

    /**
     * 超时条件更新落地面（claim/clear 参数化 SQL）
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 留痕行转换器（从表落库消费面；红阶段冻结的构造器签名不含本
     * 依赖——单测不触达落库路径，以字段注入补齐装配面）
     */
    @Autowired
    private PaymentCallbackRecordConvertor callbackConvertor;

    /**
     * 构造支付单仓储。
     *
     * @param repository               支付单主表 JPA 仓储
     * @param convertor                支付单聚合转换器（主表行 + 留痕集合）
     * @param changeTrackerProvider    变更追踪器提供者
     * @param callbackLogJpaRepository 回调留痕从表 JPA 仓储
     * @param jdbcTemplate             超时条件更新落地面
     */
    public PaymentOrderRepositoryImpl(PaymentOrderJpaRepository repository,
                                      PaymentOrderConvertor convertor,
                                      ChangeTrackerProvider changeTrackerProvider,
                                      PaymentCallbackLogJpaRepository callbackLogJpaRepository,
                                      JdbcTemplate jdbcTemplate) {
        super(repository, convertor, changeTrackerProvider);
        this.callbackLogJpaRepository = callbackLogJpaRepository;
        this.jpaRepository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 留痕行按加载序读出（ID 升序），作为聚合恢复的 other 输入。
     */
    @Override
    protected List<PaymentCallbackLogPO> getOther(PaymentOrderPO po) {
        return callbackLogJpaRepository.findByPaymentOrderIdOrderByIdAsc(po.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 聚合根主键 = 支付单独立主键（paymentOrderId）。
     */
    @Override
    protected Long retrieveIDFromRoot(PaymentOrder root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 插入根行（支付单首次落库；留痕行经回调用例追加路径插行）。
     */
    @Override
    protected void doInsert(PaymentOrder root) {
        jpaRepository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 变更集驱动落库：根行字段变更（状态/流水号）整行更新（JPA merge
     * 语义，避免逐字段映射漂移）+ 留痕追加插行（appendCallbackRecord
     * 追加 → ItemAddedChange → 从表 insert，Cart/CartItem 判例同构）。
     */
    @Override
    protected void doUpdate(PaymentOrder root, ChangeSet changeSet) {
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
     */
    @Override
    public PaymentOrder findByPayNo(String payNo) {
        return jpaRepository.findByPayNo(payNo)
                .map(po -> convertor.convertToRoot(po, getOther(po)))
                .orElse(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PaymentOrder findByOrderId(Long orderId) {
        return jpaRepository.findByOrderId(orderId)
                .map(po -> convertor.convertToRoot(po, getOther(po)))
                .orElse(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<PaymentOrder> findDueByStatusAndTimeoutAtBefore(PaymentOrderStatus status,
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
    public boolean claimTimeout(Long id, PaymentOrderStatus expectedStatus) {
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
     * 删除支付单：委托 {@link #deleteByID}。
     */
    @Override
    public int delete(PaymentOrder paymentOrder) {
        return paymentOrder == null ? 0 : deleteByID(paymentOrder.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 级联删除：从表留痕行按支付单主键整批删除（加载集合 →
     * deleteAll），再删根行；返回根行删除条数（0/1 真实语义，非
     * 契约形）。事务边界由用例层持有。
     */
    @Override
    public int deleteByID(Long paymentOrderId) {
        if (paymentOrderId == null || !jpaRepository.existsById(paymentOrderId)) {
            return 0;
        }
        final List<PaymentCallbackLogPO> logs =
                callbackLogJpaRepository.findByPaymentOrderIdOrderByIdAsc(paymentOrderId);
        if (!logs.isEmpty()) {
            callbackLogJpaRepository.deleteAll(logs);
        }
        jpaRepository.deleteById(paymentOrderId);
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
     * @param root     支付单聚合
     * @param recordId 留痕记录 ID
     * @return 留痕记录；不存在返回空
     */
    private static Optional<PaymentCallbackRecord> findRecord(PaymentOrder root, Long recordId) {
        return root.getCallbacks().stream()
                .filter(record -> recordId != null && recordId.equals(record.getId()))
                .findFirst();
    }

    /**
     * 从 root 中按变更路径里的集合项 ID 找到留痕，整行更新。
     *
     * @param root   支付单聚合
     * @param change 字段变更（path 形如 callbacks[&lt;id&gt;].field）
     */
    private void saveChangedRecord(PaymentOrder root, Change change) {
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