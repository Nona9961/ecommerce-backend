package com.nona.inf.context;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 由Spring管理的线程上下文，可以理解为一个ThreadLocal。 在bean中注入该类其实是一个proxy，每个线程之间是隔离的，和注入HttpServletRequest类似。
 *
 * @author nona
 */
@RequestScope
@Component
@Data
@ScaffoldGenerated
public class ThreadContext {

    /**
     * 当前租户 ID
     */
    private String tenantID;

    /**
     * 当前角色列表（多角色支持）
     */
    private List<String> role;

    /**
     * 请求者身份标识（token / userId / apiKey 等）
     */
    private String identity;

    /**
     * 根对象快照存储（key 为 ID）
     */
    private Map<Long, Object> snapshots = new ConcurrentHashMap<>(8);

    /**
     * 请求级属性存储（key-value）
     */
    private Map<String, Object> attributes = new ConcurrentHashMap<>(4);

    /**
     * 保存根对象快照。
     *
     * @param id   根对象 ID
     * @param root 根对象
     */
    public void saveSnapshot(Long id, Object root) {
        snapshots.put(id, root);
    }

    /**
     * 获取根对象快照。
     *
     * @param id        根对象 ID
     * @param reference 类型引用（用于泛型推导）
     * @return 根对象；不存在时返回 null
     * @param <T> 根对象类型
     */
    @SuppressWarnings("unchecked")
    public <T> T getSnapshot(Long id, TypeReference<T> reference) {
        return (T) snapshots.get(id);
    }

    /**
     * 设置请求级属性。
     *
     * @param key   属性名
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * 获取请求级属性。
     *
     * @param key 属性名
     * @return 属性值；不存在时返回 null
     * @param <T> 属性值类型
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * 移除请求级属性。
     *
     * @param key 属性名
     */
    public void removeAttribute(String key) {
        attributes.remove(key);
    }

}
