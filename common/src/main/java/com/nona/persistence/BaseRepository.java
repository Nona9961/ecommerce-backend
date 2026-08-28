package com.nona.persistence;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 基础仓储接口
 *
 * @param <ID> id类型
 * @param <Root>  聚合根类型
 */
@ScaffoldGenerated
public interface BaseRepository<ID, Root> {

    /**
     * 按 ID 获取聚合根。
     *
     * @param id 主键
     * @return 聚合根；不存在时返回 {@code null}
     */
    Root getByID(ID id);

    /**
     * 保存聚合根（新增或更新）。
     *
     * @param domain 聚合根
     * @return 是否有变更被持久化
     */
    boolean save(Root domain);

    /**
     * 删除聚合根。
     *
     * @param domain 聚合根
     * @return 删除的条数
     */
    int delete(Root domain);

    /**
     * 按 ID 删除聚合根。
     *
     * @param id 主键
     * @return 删除的条数
     */
    int deleteByID(ID id);
}