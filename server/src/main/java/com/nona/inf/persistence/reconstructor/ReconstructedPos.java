package com.nona.inf.persistence.reconstructor;

import java.util.List;
import com.nona.annotation.ScaffoldGenerated;

/**
 * PO 重建结果
 *
 * @param toSave   需要保存的 PO 列表
 * @param toDelete 需要删除的 PO 信息列表
 * @author nona
 */
@ScaffoldGenerated
public record ReconstructedPos(List<Object> toSave, List<DeletionInfo> toDelete) {

    /**
     * 按类型过滤需要保存的 PO
     *
     * @param poClass PO 类型
     * @return 该类型的 PO 列表
     * @param <T> PO 类型参数
     */
    public <T> List<T> getToSave(Class<T> poClass) {
        return toSave.stream()
                .filter(poClass::isInstance)
                .map(poClass::cast)
                .toList();
    }

    /**
     * 按类型过滤需要删除的 ID
     *
     * @param poClass PO 类型
     * @return 该类型的主键 ID 列表
     */
    public List<Long> getToDeleteIds(Class<?> poClass) {
        return toDelete.stream()
                .filter(info -> info.poClass().equals(poClass))
                .map(DeletionInfo::id)
                .toList();
    }
}
