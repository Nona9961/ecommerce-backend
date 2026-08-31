package com.nona.inf.replica;

/**
 * 写后自读窗口埋点：写用例完成时调用，标记账号在写后窗口内，
 * 搜索入口据此临时路由主库保证 read-your-writes。
 * <p>
 * 语义：窗口按账号维度（负载影响面最小）；Redis 故障 = 窗口失效降级
 * （回落到「可能旧一秒」，可接受）。接口随身份域落地提供，写用例
 * （入驻审核/商品/订单等）落地时调用。
 *
 * @author nona9961
 */
public interface LastWriteMarker {

    /**
     * 标记写后窗口（账号维度的写入埋点）。
     *
     * @param uid 写者用户 ID
     */
    void markWrite(Long uid);

    /**
     * 检查账号是否处于写后窗口内。
     *
     * @param uid 用户 ID
     * @return 窗口内返回 true；窗口外或标记设施故障返回 false（降级走 PG 读库）
     */
    boolean isWithinWriteWindow(Long uid);
}
