package com.nona.util;


import com.nona.persistence.Sequence;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 分布式 ID 生成工具：基于 Snowflake 算法（{@link Sequence}）。
 * <p>
 * 所有聚合根创建统一通过 {@link #generateID()} 获取 ID，禁止手动赋值。
 */
@ScaffoldGenerated
public class IDUtils {

    /**
     * 全局序列生成器（固定机房 1 / 机器 1）
     */
    private static final Sequence sequence = new Sequence(1, 1);

    /**
     * 私有构造器，禁止实例化。
     *
     * @throws IllegalAccessException 总是抛出
     */
    private IDUtils() throws IllegalAccessException {
        throw new IllegalAccessException();
    }

    /**
     * 生成下一个分布式 ID。
     *
     * @return 全局唯一、趋势递增的 Long ID
     */
    public static Long generateID() {
        return sequence.nextId();
    }

}
