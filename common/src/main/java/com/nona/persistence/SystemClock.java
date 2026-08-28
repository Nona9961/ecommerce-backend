package com.nona.persistence;


import java.sql.Timestamp;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.nona.annotation.ScaffoldGenerated;

/**
 * System Clock
 * <p>
 * 利用ScheduledExecutorService实现高并发场景下System.curentTimeMillis()的性能问题的优化.
 *
 * @author lry
 */
@ScaffoldGenerated
public enum SystemClock {

    INSTANCE(1);

    /**
     * 时钟刷新周期（毫秒）
     */
    private final long period;

    /**
     * 缓存的最新时间戳
     */
    private final AtomicLong nowTime;

    /**
     * 是否已初始化
     */
    private boolean started = false;

    /**
     * 定时刷新任务执行器
     */
    private ScheduledExecutorService executorService;

    /**
     * 枚举构造器。
     *
     * @param period 时钟刷新周期（毫秒）
     */
    SystemClock(long period) {
        this.period = period;
        this.nowTime = new AtomicLong(System.currentTimeMillis());
    }

    /**
     * The initialize scheduled executor service
     */
    public void initialize() {
        if (started) {
            return;
        }

        this.executorService = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "system-clock");
            thread.setDaemon(true);
            return thread;
        });
        executorService.scheduleAtFixedRate(() -> nowTime.set(System.currentTimeMillis()),
                this.period, this.period, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::destroy));
        started = true;
    }

    /**
     * The get current time milliseconds
     *
     * @return long time
     */
    public long currentTimeMillis() {
        return started ? nowTime.get() : System.currentTimeMillis();
    }

    /**
     * The get string current time
     *
     * @return string time
     */
    public String currentTime() {
        return new Timestamp(currentTimeMillis()).toString();
    }

    /**
     * The destroy of executor service
     */
    public void destroy() {
        if (executorService != null) {
            executorService.shutdown();
        }
    }

}