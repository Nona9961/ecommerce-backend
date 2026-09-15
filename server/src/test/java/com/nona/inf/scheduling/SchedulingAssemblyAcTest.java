package com.nona.inf.scheduling;

import com.nona.inf.logistics.LogisticsSimulator;
import com.nona.inf.timeout.TimeoutScheduler;
import com.nona.inf.timeout.TimeoutTaskProcessor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

/**
 * 调度装配验收测试（调度激活隔离面：mock 测不到的装配面——使能
 * 开关键 / 扫描入口注解 / 门控不激活语义 / 手触入口可用性，-Pfull 验收面）。
 * <p>
 * <b>冻结策略</b>：
 * {@code @EnableScheduling} 挂 {@code @ConditionalOnProperty(nona.scheduling.enabled,
 * havingValue=true)} 门控配置类——dev=true（demo 真实滴答）、
 * test=false（测试手触 processOne/scanAndAdvance，调度装配经本类
 * 断言）。本类语义绑定 test profile（运行命令模板统一带
 * {@code -Dspring.profiles.active=test}）；若在 dev 下误跑，
 * 使能键断言将如实失败（dev 语义即 true，见 application-dev.yml）。
 * <p>
 * <b>交付形态</b>：本类以真实断言体交付——使能开关键与扫描入口
 * 注解两断言钉死调度隔离契约（yml 落键 + 注解挂点）；余下断言为已落地面
 * 的回归锚点（test=false 下无 @EnableScheduling 激活、
 * 手触入口 bean 可用），恒绿以防护误激活与装配回退。
 * <p>
 * 与真推进面分工：本类只断言「调度装配不激活 + 入口可注入可调用」；
 * 推进/签收联动的真实执行面归 LogisticsSimulatorSmokeTest（手触推进，
 * 手触 scanAndAdvance，自有数据锚定）——本类<u>不裸调</u>
 * {@code scanAndAdvance}：运单表为 global（无租户过滤面），共享真库
 * 上的裸调用会推进库中全部到期在途运单（副作用面不可控）。
 *
 * @author nona9961
 */
@SpringBootTest
class SchedulingAssemblyAcTest {

    /**
     * 上下文（门控激活面检查）
     */
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 配置环境（使能开关键断言）
     */
    @Autowired
    private Environment environment;

    /**
     * 物流模拟推进器（已注册 4 参装配构造——手触入口注入面）
     */
    @Autowired
    private LogisticsSimulator logisticsSimulator;

    /**
     * 冒烟-1 使能开关键：test 侧 {@code nona.scheduling.enabled}=false
     * （键缺失 = 配置漂移，@ConditionalOnProperty 默认不激活）。false
     * 语义 = 全局调度关闭（两个
     * 引擎的 @Scheduled 都不生效），冒烟手触不受调度线程干扰。
     */
    @Test
    @DisplayName("冒烟-1 调度使能键：test 侧 nona.scheduling.enabled=false")
    void schedulingSwitch_testSideDisabled() {
        Assertions.assertEquals("false",
                environment.getProperty("nona.scheduling.enabled"),
                "application-test.yml 必须显式声明 nona.scheduling.enabled=false"
                        + "（调度激活隔离策略，缺失时 @ConditionalOnProperty 默认不激活"
                        + "——键缺失属配置漂移，断言即红以驱动落键）");
    }

    /**
     * 冒烟-2 模拟器扫描入口调度注解：{@code LogisticsSimulator#scanAndAdvance}
     * 必须挂 {@code @Scheduled}，扫描周期配置键按类 javadoc 冻结为
     * {@code ${nona.logistics.scan-interval-ms:5000}}（注解缺失即红，
     * 防护挂点回退）。
     */
    @Test
    @DisplayName("冒烟-2 调度挂点：scanAndAdvance 带 @Scheduled（scan-interval-ms 键）")
    void simulatorScanEntry_scheduledAnnotated() throws NoSuchMethodException {
        final Method scan = LogisticsSimulator.class.getMethod("scanAndAdvance");
        final Scheduled scheduled = scan.getAnnotation(Scheduled.class);
        Assertions.assertNotNull(scheduled,
                "scanAndAdvance 必须挂 @Scheduled（模拟器扫描入口调度位）");
        Assertions.assertEquals("${nona.logistics.scan-interval-ms:5000}",
                scheduled.fixedDelayString(),
                "扫描间隔配置键必须按 javadoc 冻结形态（默认 5000ms 可配置覆盖）");
    }

    /**
     * 冒烟-3 超时引擎扫描入口调度注解（回归锚点，恒绿）：既定冻结
     * {@code TimeoutScheduler#scan} 的 {@code @Scheduled}（30 秒周期）——
     * 本断言防装配回归（误删注解/改键名）。调度是否生效仍由冒烟-4 的
     * 门控不激活断言把关。
     */
    @Test
    @DisplayName("冒烟-3 调度挂点：TimeoutScheduler.scan 带 @Scheduled（回归锚点）")
    void timeoutScanEntry_scheduledAnnotated() throws NoSuchMethodException {
        final Method scan = TimeoutScheduler.class.getMethod("scan");
        final Scheduled scheduled = scan.getAnnotation(Scheduled.class);
        Assertions.assertNotNull(scheduled, "TimeoutScheduler.scan 调度注解为既有冻结契约");
        Assertions.assertEquals("${nona.timeout.scan-interval-ms:30000}",
                scheduled.fixedDelayString(), "超时扫描间隔键名不得漂移");
    }

    /**
     * 冒烟-4 门控不激活语义（回归锚点，恒绿）：test=false 时容器内不得
     * 存在任何带 {@code @EnableScheduling} 的配置类，也不得注册
     * {@link TaskScheduler}（Boot 仅在有调度装配时经
     * ScheduledAnnotationBeanPostProcessor 提供调度器）——两断言合起来
     * 锁死「test 侧调度装配不激活」；若门控配置类放错位置（绕开
     * @ConditionalOnProperty 或属性误激活），本断言首先红。
     */
    @Test
    @DisplayName("冒烟-4 门控不激活：无 @EnableScheduling 配置类 + 无 TaskScheduler bean")
    void gate_testSideSchedulingInactive() {
        Assertions.assertTrue(
                applicationContext.getBeansWithAnnotation(EnableScheduling.class).isEmpty(),
                "test 侧不得加载任何 @EnableScheduling 配置类"
                        + "（调度隔离策略：@ConditionalOnProperty(nona.scheduling.enabled,"
                        + " havingValue=true)，test=false 即不激活）");
        Assertions.assertTrue(
                applicationContext.getBeansOfType(TaskScheduler.class).isEmpty(),
                "test 侧不得存在 TaskScheduler bean（无调度装配 = 无调度线程）");
    }

    /**
     * 冒烟-5 手触入口可用性（回归锚点，恒绿）：调度关闭不消灭入口——
     * 推进器与处理器仍以普通 bean 存在、扫描入口方法签名可经反射调用
     * （冒烟以直接方法调用代替调度触发，契约见调度隔离策略）。不
     * 执行 {@code scanAndAdvance} 函数体：运单为 global 表（无租户过滤
     * 面），共享真库上裸调会推进全部到期在途运单（副作用面归
     * LogisticsSimulatorSmokeTest 以自有数据锚定，别处不裸调）。
     */
    @Test
    @DisplayName("冒烟-5 手触入口：simulator/processor bean 就位 + 扫描入口可调用")
    void manualTrigger_entriesAvailable() throws NoSuchMethodException {
        Assertions.assertNotNull(logisticsSimulator,
                "LogisticsSimulator bean 必须存在（4 参装配构造注册面）");
        Assertions.assertNotNull(applicationContext.getBean(TimeoutTaskProcessor.class),
                "TimeoutTaskProcessor bean 必须存在（超时引擎手触入口）");
        Assertions.assertNotNull(LogisticsSimulator.class.getMethod("scanAndAdvance"),
                "scanAndAdvance 签名必须可直达（手触推进入口，调度关闭仍可用）");
        Assertions.assertNotNull(TimeoutScheduler.class.getMethod("scan"),
                "TimeoutScheduler.scan 签名必须可直达（手触扫描入口）");
    }
}