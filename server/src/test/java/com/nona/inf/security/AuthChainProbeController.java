package com.nona.inf.security;

import com.nona.inf.context.ThreadContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证链路探测端点（测试支撑）：回显当前请求的 ThreadContext 快照，
 * 用于断言认证过滤器是否正确组装用户上下文。
 * <p>
 * 仅存在于测试 classpath，不进入生产制品。
 */
@RestController
public class AuthChainProbeController {

    /**
     * 当前请求上下文（request 作用域代理）
     */
    private final ThreadContext threadContext;

    /**
     * 构造探测控制器。
     *
     * @param threadContext 请求上下文
     */
    public AuthChainProbeController(ThreadContext threadContext) {
        this.threadContext = threadContext;
    }

    /**
     * 买家端探测：GET /mall/probe。
     *
     * @return 当前请求的上下文快照
     */
    @GetMapping("/mall/probe")
    public ProbeSnapshot mall() {
        return snapshot();
    }

    /**
     * 商家端探测：GET /seller/probe。
     *
     * @return 当前请求的上下文快照
     */
    @GetMapping("/seller/probe")
    public ProbeSnapshot seller() {
        return snapshot();
    }

    /**
     * 平台端探测：GET /admin/probe。
     *
     * @return 当前请求的上下文快照
     */
    @GetMapping("/admin/probe")
    public ProbeSnapshot admin() {
        return snapshot();
    }

    /**
     * 采集当前请求的上下文快照。
     *
     * @return 身份、角色与租户快照
     */
    private ProbeSnapshot snapshot() {
        return new ProbeSnapshot(threadContext.getIdentity(), threadContext.getRole(), threadContext.getTenantID());
    }

    /**
     * 上下文快照：认证过滤器组装结果的可见形态。
     *
     * @param identity 请求者身份（用户 ID 字符串）
     * @param roles    角色列表
     * @param tenantID 租户 ID（认证阶段恒为 null，由店铺归属校验阶段填充）
     */
    public record ProbeSnapshot(String identity, List<String> roles, String tenantID) {
    }
}