package com.nona.inf.persistence.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.inf.context.ThreadContext;
import com.nona.inf.persistence.converters.RdbGeneralConvertor;
import com.nona.inf.persistence.po.BasePO;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import com.nona.persistence.BaseRepository;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.data.repository.ListCrudRepository;

import java.util.Objects;
import java.util.Optional;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 支持基于diff的仓储，集成 ChangeTracker 实现变更追踪
 *
 * @param <Root>  聚合根
 * @param <PO>    PO对象
 * @param <Other> 聚合根需要的其他对象，详见{@link RdbGeneralConvertor}
 * @see RdbGeneralConvertor
 */
@RequiredArgsConstructor
@ScaffoldGenerated
public abstract class DifferRepository<Root, PO extends BasePO, Other> implements BaseRepository<Long, Root> {

    /**
     * ThreadContext 属性中 ChangeTracker 的键名
     */
    private static final String TRACKER_KEY = "CHANGE_TRACKER";

    /**
     * JPA 仓储（Spring Data）
     */
    protected final ListCrudRepository<PO, Long> repository;

    /**
     * 请求级上下文（持有追踪器与根对象快照）
     */
    protected final ThreadContext threadContext;

    /**
     * DO ↔ PO 转换器
     */
    protected final RdbGeneralConvertor<Root, PO, Other> convertor;

    /**
     * ChangeTracker 提供者（创建请求级追踪器）
     */
    protected final ChangeTrackerProvider changeTrackerProvider;

    /**
     * 根对象类型引用（快照存取用）
     */
    private final TypeReference<Root> rootType = new TypeReference<>() {};

    /**
     * {@inheritDoc}
     * <p>
     * 读取成功后登记到 ChangeTracker 建立快照基线，并将根对象存入请求上下文。
     */
    @Override
    public Root getByID(Long id) {
        final Optional<PO> poOptional = repository.findById(id);
        if (poOptional.isEmpty()) {
            return null;
        }
        final PO po = poOptional.get();
        final Root root = convertor.convertToRoot(po, getOther(po));
        if (root == null) {
            return null;
        }

        getOrCreateChangeTracker().track(root);
        threadContext.saveSnapshot(id, root);
        return root;
    }

    /**
     * 获取other对象，默认实现为不需要other对象
     *
     * @param po po
     * @return The other object, or null if no other object is required.
     */
    @Nullable
    protected Other getOther(PO po) {
        return null;
    }

    /**
     * 从聚合根提取主键 ID（子类实现）。
     *
     * @param root 聚合根
     * @return 主键 ID
     */
    protected abstract Long retrieveIDFromRoot(Root root);

    /**
     * 新增聚合根（子类实现）
     * <p>
     * 实现方式由子类决定：JDBC / JPA / 混合模式
     *
     * @param root 聚合根
     */
    protected abstract void doInsert(Root root);

    /**
     * 更新聚合根（子类实现）
     * <p>
     * 可选实现方式：
     * <ul>
     *     <li>遍历 changeSet.getLeafChanges() 手动生成 SQL (JDBC)</li>
     *     <li>从 ChangeSet 更新 PO，让 JPA 生成 SQL</li>
     *     <li>利用 changeSet.getAllChanges() 的树形结构递归处理</li>
     * </ul>
     *
     * @param root      当前聚合根状态
     * @param changeSet 变更集（包含完整树形结构）
     */
    protected abstract void doUpdate(Root root, ChangeSet changeSet);

    /**
     * {@inheritDoc}
     * <p>
     * 新增：直接插入并登记追踪；更新：计算变更集，非空才执行 {@link #doUpdate}，
     * 完成后重新登记快照基线。
     */
    @Override
    public boolean save(Root root) {
        Objects.requireNonNull(root);
        final Long id = retrieveIDFromRoot(root);
        final ChangeTracker changeTracker = getOrCreateChangeTracker();

        if (!isTracked(id)) {
            doInsert(root);
            changeTracker.track(root);
            threadContext.saveSnapshot(id, root);
            return true;
        }

        final ChangeSet changeSet = changeTracker.calculateChanges();
        if (changeSet.isEmpty()) {
            return false;
        }

        doUpdate(root, changeSet);

        changeTracker.track(root);
        threadContext.saveSnapshot(id, root);
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 当前模板未实现删除语义，返回 0。
     */
    @Override
    public int delete(Root root) {
        return 0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 当前模板未实现删除语义，返回 0。
     */
    @Override
    public int deleteByID(Long id) {
        return 0;
    }

    /**
     * 检查该 ID 的根对象是否已被追踪（快照是否已建立）。
     *
     * @param id 主键 ID
     * @return 已追踪返回 true
     */
    private boolean isTracked(Long id) {
        return threadContext.getSnapshot(id, rootType) != null;
    }

    /**
     * 获取或创建 ChangeTracker（请求级别单例）
     */
    protected ChangeTracker getOrCreateChangeTracker() {
        ChangeTracker changeTracker = threadContext.getAttribute(TRACKER_KEY);
        if (changeTracker == null) {
            final ChangeTracker newTracker = changeTrackerProvider.create();
            changeTracker = newTracker;
            threadContext.setAttribute(TRACKER_KEY, newTracker);
        }
        return changeTracker;
    }
}
