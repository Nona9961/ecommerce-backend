package com.nona.inf.persistence.converters;
import com.nona.annotation.ScaffoldGenerated;

/**
 * PO 转换器接口（简单实体）
 * <p>
 * DO ↔ PO 一一映射，用户需自行实现转换逻辑。
 *
 * @param <DO> 领域对象类型
 * @param <PO> 持久化对象类型
 */
@ScaffoldGenerated
public interface PoConverter<DO, PO> {

    /**
     * 获取领域对象类型。
     *
     * @return 领域对象类型
     */
    Class<DO> domainClass();

    /**
     * 获取持久化对象类型。
     *
     * @return 持久化对象类型
     */
    Class<PO> poClass();

    /**
     * 领域对象转持久化对象。
     *
     * @param domain 领域对象
     * @return PO 对象
     */
    PO toPO(DO domain);

    /**
     * 持久化对象转领域对象。
     *
     * @param po PO 对象
     * @return 领域对象
     */
    DO toDomain(PO po);
}
