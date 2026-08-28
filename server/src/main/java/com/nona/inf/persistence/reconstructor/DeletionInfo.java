package com.nona.inf.persistence.reconstructor;

import java.util.Objects;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 删除信息（PO 类型 + 主键）
 * <p>
 * id 类型为 Long，与 BasePO.id 保持一致
 * </p>
 *
 * @param poClass 待删除的 PO 类型
 * @param id      待删除的主键
 * @author nona
 */
@ScaffoldGenerated
public record DeletionInfo(Class<?> poClass, Long id) {

    /**
     * 紧凑构造器：校验参数非空。
     *
     * @throws NullPointerException poClass 或 id 为 null 时抛出
     */
    public DeletionInfo {
        Objects.requireNonNull(poClass, "poClass must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }
}
