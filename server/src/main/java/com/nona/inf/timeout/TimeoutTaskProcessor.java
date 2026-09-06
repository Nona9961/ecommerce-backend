package com.nona.inf.timeout;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 超时任务处理器：单条候选「认领 → 执行 → 清除」的同事务编排。
 * <p>
 * 事务边界（引擎的关键架构点）：认领更新与处理器执行在同一本地事务内
 * ——处理器抛异常/崩溃则整体回滚，认领位回到 0，候选在下轮扫描被再次
 * 认领处理；恢复依赖重扫闭环，无需额外补偿。处理成功才清除截止时间
 * （清除后行退出候选），失败路径不产生滞留的认领位——两种路径都不
 * 存在「死认领行」。
 * <p>
 * 处理器实现内调用的业务用例方法以默认传播加入本事务；本类的事务
 * 注解经 Spring 代理生效，直接手工装配的单元测试验证纯编排逻辑。
 */
@Component
public class TimeoutTaskProcessor {

    /**
     * 处理器注册表（按类型路由到期处理动作）。
     */
    private final TimeoutHandlerRegistry registry;

    /**
     * @param registry 处理器注册表
     */
    public TimeoutTaskProcessor(TimeoutHandlerRegistry registry) {
        this.registry = registry;
    }

    /**
     * 处理单条候选：认领成功才执行，执行成功才清除；认领失败立即跳过
     * （他方已处理或状态已迁移，不重复执行）；处理器缺失属装配错误，
     * 在认领前即拒绝（不触碰数据）。
     *
     * @param store 该候选所属的数据端口
     * @param task  到期候选
     * @param <T>   业务对象引用类型
     * @return true = 认领并处理完成；false = 认领失败（本轮放弃）
     */
    @Transactional
    public <T> boolean processOne(TimeoutTaskStore<T> store, TimeoutTask<T> task) {
        TimeoutHandler<T> handler = registry.get(store.type());
        if (!store.claim(task)) {
            return false;
        }
        handler.fire(task.target());
        store.clearDeadline(task);
        return true;
    }
}