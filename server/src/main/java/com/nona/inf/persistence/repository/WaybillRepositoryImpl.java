package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.domain.logistics.repo.WaybillRepository;
import com.nona.inf.persistence.converters.WaybillConvertor;
import com.nona.inf.persistence.converters.WaybillTrackConvertor;
import com.nona.inf.persistence.po.logistics.WaybillPO;
import com.nona.inf.persistence.po.logistics.WaybillTrackPO;
import com.nona.inf.persistence.repository.jpa.WaybillJpaRepository;
import com.nona.inf.persistence.repository.jpa.WaybillTrackJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 运单仓储落地：继承 {@link DifferRepository}（主表快照 + 变更追踪），
 * 主表 waybill（global——租户中立，经 subOrderId 关联订单域）+ 轨迹
 * 从表 waybill_track（rootId 关联，append-only，加载序 ID 升序）。
 * <p>
 * 读：主键装载走基类模板（getOther 经从表 JPA 按 waybillId 反查轨迹
 * 行集合，聚合内存装配）；在途锚点（findInTransitBySubOrderId——
 * 「一子单一在途」不变量查询锚点，发货编排重复发货防查）与在途全量
 * 扫描（findInTransit——模拟推进器扫描锚点，业务节奏判定收敛在推进
 * 器不穿透仓储）为 WU-35/36 冻结契约；按子单装载（findBySubOrderId，
 * WU-55 冻结）为订单详情/物流展示面锚点（含签收终态，历史并存行取
 * 最新一张）。写：轨迹从表 append-only 追加插行（advance 推进 → 聚合
 * 方法构造轨迹行 → 变更集 ItemAddedChange 插行）。
 * <p>
 * 删除：deleteByID 级联删从表轨迹行（按运单主键）+ 主表行后删，返回
 * 真实删除条数（0/1）。
 *
 * @author nona9961
 */
@Component
public class WaybillRepositoryImpl
        extends DifferRepository<Waybill, WaybillPO, List<WaybillTrackPO>>
        implements WaybillRepository {

    /**
     * 轨迹集合在聚合中的字段名（变更集路径解析用）
     */
    private static final String TRACKS_FIELD = "tracks";

    /**
     * 轨迹从表 JPA 仓储（getOther 装载/级联删/变更集插行消费面）
     */
    private final WaybillTrackJpaRepository trackJpaRepository;

    /**
     * 主表 JPA 仓储（业务关联装载/扫描面——父类 repository 字段为
     * 泛型契约类型，本类显式持有具体面）
     */
    private final WaybillJpaRepository jpaRepository;

    /**
     * 轨迹行转换器（从表落库消费面；红阶段冻结的构造器签名不含本
     * 依赖——单测不触达落库路径，以字段注入补齐装配面）
     */
    @Autowired
    private WaybillTrackConvertor trackConvertor;

    /**
     * 构造运单仓储。
     *
     * @param repository          运单主表 JPA 仓储
     * @param convertor           运单聚合转换器（主表行 + 轨迹集合）
     * @param changeTrackerProvider 变更追踪器提供者
     * @param trackJpaRepository  轨迹从表 JPA 仓储
     */
    public WaybillRepositoryImpl(WaybillJpaRepository repository,
                                 WaybillConvertor convertor,
                                 ChangeTrackerProvider changeTrackerProvider,
                                 WaybillTrackJpaRepository trackJpaRepository) {
        super(repository, convertor, changeTrackerProvider);
        this.trackJpaRepository = trackJpaRepository;
        this.jpaRepository = repository;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 轨迹行按加载序读出（ID 升序），作为聚合恢复的 other 输入。
     */
    @Override
    protected List<WaybillTrackPO> getOther(WaybillPO po) {
        return trackJpaRepository.findByWaybillIdOrderByIdAsc(po.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 聚合根主键 = 运单独立主键（waybillId）。
     */
    @Override
    protected Long retrieveIDFromRoot(Waybill root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 插入根行 + 初始轨迹行（发货创建运单首次落库）。
     */
    @Override
    protected void doInsert(Waybill root) {
        jpaRepository.save(convertor.convertToPO(root));
        for (final WaybillTrack track : root.getTracks()) {
            trackJpaRepository.save(trackConvertor.toPO(track));
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 变更集驱动落库：根行字段变更（状态/in_transit 位）整行更新
     * （JPA merge 语义）+ 轨迹追加插行（advance 推进 →
     * ItemAddedChange → 从表 insert，判例同构）。
     */
    @Override
    protected void doUpdate(Waybill root, ChangeSet changeSet) {
        boolean rootChanged = false;
        for (final Change change : changeSet.getLeafChanges()) {
            final String collectionField = change.collectionFieldName();
            if (collectionField == null) {
                rootChanged = true;
                continue;
            }
            if (!TRACKS_FIELD.equals(collectionField)) {
                continue;
            }
            if (change instanceof ItemAddedChange added) {
                final Long trackId = extractIdentifier(added.addedItem());
                findTrack(root, trackId)
                        .ifPresent(track -> trackJpaRepository.save(trackConvertor.toPO(track)));
            } else if (change instanceof ItemRemovedChange removed) {
                final Long trackId = extractIdentifier(removed.removedItem());
                if (trackId != null) {
                    trackJpaRepository.deleteById(trackId);
                }
            } else {
                // ValueChange / ObjectFieldChange：整行更新，避免逐字段映射漂移
                saveChangedTrack(root, change);
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
    public Optional<Waybill> findInTransitBySubOrderId(Long subOrderId) {
        return jpaRepository.findBySubOrderIdAndInTransitTrue(subOrderId)
                .map(po -> convertor.convertToRoot(po, getOther(po)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Waybill> findInTransit() {
        return jpaRepository.findByInTransitTrue().stream()
                .map(po -> convertor.convertToRoot(po, getOther(po)))
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Waybill> findBySubOrderId(Long subOrderId) {
        return jpaRepository.findFirstBySubOrderIdOrderByIdDesc(subOrderId)
                .map(po -> convertor.convertToRoot(po, getOther(po)));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除运单：委托 {@link #deleteByID}。
     */
    @Override
    public int delete(Waybill waybill) {
        return waybill == null ? 0 : deleteByID(waybill.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 级联删除：从表轨迹行按运单主键先删，再删根行；返回根行删除
     * 条数（0/1 真实语义，非契约形）。事务边界由用例层持有。
     */
    @Override
    public int deleteByID(Long waybillId) {
        if (waybillId == null || !jpaRepository.existsById(waybillId)) {
            return 0;
        }
        trackJpaRepository.deleteByWaybillId(waybillId);
        jpaRepository.deleteById(waybillId);
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
     * 从聚合轨迹集合中按轨迹 ID 定位实体（整行更新/新增落库前取最新态）。
     *
     * @param root    运单聚合
     * @param trackId 轨迹 ID
     * @return 轨迹；不存在返回空
     */
    private static Optional<WaybillTrack> findTrack(Waybill root, Long trackId) {
        return root.getTracks().stream()
                .filter(track -> trackId != null && trackId.equals(track.getId()))
                .findFirst();
    }

    /**
     * 从 root 中按变更路径里的集合项 ID 找到轨迹，整行更新。
     *
     * @param root   运单聚合
     * @param change 字段变更（path 形如 tracks[&lt;id&gt;].field）
     */
    private void saveChangedTrack(Waybill root, Change change) {
        final Long trackId = extractIdFromPath(change.path());
        if (trackId != null) {
            findTrack(root, trackId)
                    .ifPresent(track -> trackJpaRepository.save(trackConvertor.toPO(track)));
        }
    }

    /**
     * 从变更路径提取集合项 ID（path 形如 tracks[&lt;id&gt;].field）。
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