package com.nona.inf.replica;

import com.nona.domain.search.ports.SearchWriteWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 写后窗口判定默认实现（Redis 剩余 TTL 判定，TD-08 3s 业务窗口）。
 * <p>
 * 实现契约：
 * <ul>
 *     <li>键位：与 {@link RedisLastWriteMarker} 同道同键
 *         （{@code lastWrite:{uid}}，{@code KEY_PREFIX} 复用）；
 *         Redis 中 TTL 5s 的标记键，剩余存活 &gt; 2s ⟺ 写入未满 3s；</li>
 *     <li>判定：{@code getExpire(lastWrite:{uid})} 返回值
 *         {@code ttl != null && ttl > 2} 判窗口内——秒级精度下边界
 *         ±1s 属预期（ttl=2 已过窗、ttl=3 仍在窗内）；
 *         键不存在（-2）/已过期（-1）/异常一律窗口外；</li>
 *     <li>故障降级：Redis 访问异常捕获为窗口外（false），不得抛出——
 *         与认证缓存/埋点设施同款故障语义（可用性不依赖本组件）；</li>
 *     <li>纯读路径：不产生任何写命令（TTL 判定不改键）。</li>
 * </ul>
 *
 * @author nona9961
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSearchWriteWindow implements SearchWriteWindow {

    /**
     * 窗口判定下界（秒）：剩余 TTL 大于该值即判定窗口内。
     * <p>
     * 推导：标记键 TTL = {@link RedisLastWriteMarker#WINDOW_SECONDS}（5s），
     * 3s 业务窗口 ⟺ 写入未满 3s ⟺ 剩余存活 &gt; 5-3=2s。
     */
    private static final long WINDOW_BOUNDARY_SECONDS = 2;

    /**
     * Redis 模板（字符串值；与埋点设施同模板，读路径）
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * {@inheritDoc}
     * <p>
     * 判定形态：{@code getExpire} 返回值 &gt; 2s 判窗口内；键缺失（-2）、
     * 已过期（-1）、null 与访问异常一律窗口外（降级走 PG 读库）。
     */
    @Override
    public boolean isWithinWriteWindow(Long uid) {
        if (uid == null) {
            return false;
        }
        try {
            final Long ttl = redisTemplate.getExpire(RedisLastWriteMarker.KEY_PREFIX + uid);
            return ttl != null && ttl > WINDOW_BOUNDARY_SECONDS;
        } catch (DataAccessException e) {
            log.warn("[last-write] 窗口判定失败（按窗口外处理，走 PG 读库） uid={}", uid, e);
            return false;
        }
    }
}