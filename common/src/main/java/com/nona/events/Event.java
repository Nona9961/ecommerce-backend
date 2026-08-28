package com.nona.events;

import java.time.Instant;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 事件
 *
 * @param <E> 事件携带的数据
 * @author nona
 */
@ScaffoldGenerated
public interface Event<E> {
    /**
     * 拿去事件数据
     *
     * @return 时间数据
     */
    E getPayload();

    /**
     * 事件类型，用于handler判断能否处理
     *
     * @return 事件类型
     */
    String getType();

    /**
     * 事件发生的时间戳
     *
     * @return 时间戳
     */
    Instant timestamp();
}
