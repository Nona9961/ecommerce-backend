package com.nona.domain.inventory.repo;

import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.persistence.BaseRepository;

import java.util.List;

/**
 * 库存流水仓储接口（inventory_log 从表持久化契约，实现在基础设施层）。
 * <p>
 * append-only 记录行仓储：本接口只提供「追加一行」与「按 SKU 分页查询」，
 * 流水行只增不改——行级删除语义不存在（接口继承的 delete/deleteByID
 * 实现按无行级删除路径处理，与商品编辑版本仓储同定式）。
 * <p>
 * 表级约束：inventory_log（tenant=shopId），幂等键 (order_id, sku_id,
 * type) 唯一约束——同一订单同一 SKU 的同一类型变动只允许一次，重复
 * 插入由唯一约束拒绝（防重复预占/扣减/回滚；手动调整型无 orderId，
 * 不参与订单幂等）。读经租户过滤（fail-closed：跨店铺 SKU 流水不可见）。
 * <p>
 * 流水的产生收敛在聚合变更方法（InventoryItem 变更返回流水行），本仓储
 * 仅承载 append 落库与最小查询面（按 SKU 分页列表 + 计数——审计/对账
 * 经本面读取，不装配进聚合内存）。
 *
 * @author nona9961
 */
public interface InventoryLogRepository extends BaseRepository<Long, InventoryLog> {

    /**
     * 追加一行流水（append-only 插入：同一 (order_id, sku_id, type)
     * 重复追加被 DB 唯一约束拒绝；租户归属沿请求上下文注入）。
     *
     * @param log 流水行（聚合变更方法构造，待追加）
     * @return 追加后的流水行（含持久化审计时间）
     */
    InventoryLog append(InventoryLog log);

    /**
     * 按 SKU 分页列出流水（新行在前——产生时间倒序）。
     *
     * @param skuId  归属 SKU ID（与租户过滤共同定位行集）
     * @param offset 首条偏移量（从 0 开始）
     * @param limit  每页条数
     * @return 流水列表；无流水为空列表
     */
    List<InventoryLog> listBySkuPaged(Long skuId, int offset, int limit);

    /**
     * 按 SKU 统计流水数（分页 total 用）。
     *
     * @param skuId 归属 SKU ID
     * @return 流水数
     */
    long countBySku(Long skuId);

    /**
     * 幂等键存在性判定：(order_id, sku_id, type) 三元组是否已有一行流水
     * （租户过滤内校验——跨店铺请求按不存在呈现，fail-closed）。
     * <p>
     * 重复请求先经本判定快速拒绝（并发窗口内的重复追加由表级唯一
     * 约束兜底，兜底路径的异常在实现层转换为业务异常——两层防线）。
     *
     * @param orderId 订单 ID（订单驱动型必填；手动调整型请勿调用本方法）
     * @param skuId   归属 SKU ID
     * @param type    流水类型
     * @return 幂等键已存在返回 true
     */
    boolean existsByIdempotencyKey(Long orderId, Long skuId, InventoryLogType type);
}