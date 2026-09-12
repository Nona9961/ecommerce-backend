package com.nona.domain.search.ports;

/**
 * 写后自读窗口判定端口（search 域消费方视角，3s 业务窗口）。
 * <p>
 * 与 {@code LastWriteMarker}（inf.replica 埋点设施）的分工：
 * <ul>
 *     <li>埋点设施：写用例完成时写 {@code lastWrite:{uid}}（TTL 5s），
 *         负责 {markWrite, isWithinWriteWindow(key 存活检查)}；</li>
 *     <li>本端口：搜索入口的<b>3s 窗口判定</b>——「3s 窗口判定由
 *         搜索消费方按剩余 TTL/写入时刻实现」的契约位（埋点实现类
 *         注释明示委托）。判定依据：键剩余 TTL &gt; 2s ⟺ 写入发生在 3s 内
 *         （TTL 5s 权威值，秒级精度边界 ±1s 属预期）。</li>
 * </ul>
 * 语义约束：
 * <ul>
 *     <li><b>窗口命中</b>：搜索本次查询走主库（read-your-writes，写者
 *         本人可见自己刚写入的内容）；</li>
 *     <li><b>降级</b>：Redis 故障/键缺失/已过窗一律返回 false——窗口
 *         失效回落「可能旧一秒」的 PG 读库，可用性不依赖本组件；</li>
 *     <li><b>账号粒度</b>：按 uid 定位，负载影响面最小；</li>
 *     <li>本端口零框架依赖（domain 契约，实现落在 inf 层）。</li>
 * </ul>
 *
 * @author nona9961
 */
public interface SearchWriteWindow {

    /**
     * 当前账号是否处于写后窗口内（3s 业务窗口）。
     *
     * @param uid 用户 ID（写者本人；null 视为窗口外）
     * @return 窗口内返回 true；窗口外、未知账号或判定设施故障返回
     *     false（降级走 PG 读库）
     */
    boolean isWithinWriteWindow(Long uid);
}