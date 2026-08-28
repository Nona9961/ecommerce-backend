package com.nona.inf.persistence.converters;

import jakarta.annotation.Nullable;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 关系型数据库 root和po的转换器
 * <p>
 * 一般来说聚合根的持久化对象是聚合根的子集，所以一般聚合根的持久化对象可以由聚合根的子集来构造，反之则不行，所以需要other辅助。
 *
 * @param <Root>  聚合根
 * @param <PO>    持久化对象
 * @param <Other> 聚合的其他信息
 * @author nona
 */
@ScaffoldGenerated
public interface RdbGeneralConvertor<Root, PO, Other> {

    /**
     * 获取聚合根类型。
     *
     * @return 聚合根类型
     */
    Class<Root> rootClass();

    /**
     * 获取 PO 类型。
     *
     * @return PO 类型
     */
    Class<PO> poClass();

    /**
     * 聚合根转 PO。
     *
     * @param root 聚合根
     * @return PO 对象
     */
    PO convertToPO(Root root);

    /**
     * PO 转聚合根。
     *
     * @param po    PO 对象
     * @param other 聚合的其他信息；可能为 null
     * @return 聚合根
     */
    Root convertToRoot(PO po, @Nullable Other other);
}
