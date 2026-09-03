package com.nona.domain.inventory.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.IDUtils;

/**
 * 库存聚合根（inventory_item 主表行，SKU 维度唯一）：可售/预占/已售三态
 * 数量 + 乐观锁版本列（available/held/sold，中文语义与领域模型
 * sellable/reserved/sold 等价；不维护总量字段——总量仅作口径说明，不落
 * 列）。所属店铺（tenant=shopId）与 SKU（跨域引用键）创建时定型不可变。
 * <p>
 * 关键不变量（全部收敛在本聚合方法内，包外无直接字段变更路径）：
 * <ol>
 *     <li>三态数量恒非负——变更守卫在聚合方法（前置校验）与仓储条件
 *         更新（并发防线，属后续阶段）双层表达；任何变更导致负值拒绝；</li>
 *     <li><b>每笔变更必有流水</b>：本聚合不存在无流水产出的变更路径——
 *         全部变更方法返回 {@link InventoryLog}（流水内嵌构造，before/after
 *         三态快照与 delta 在聚合内组装，语义最精确），聚合状态变更与
 *         流水追加同事务落库（编排在用例层：保存聚合 + 追加流水行，任一
 *         失败整体回滚——不可追踪的库存变更不可能）；</li>
 *     <li>防超卖唯一机制为数据库条件更新（属后续阶段：预占执行条件
 *         UPDATE 且可售充足判定，影响行数 0 即失败），聚合方法校验为
 *         领域前置守卫不替代并发防线；</li>
 *     <li>version 乐观锁列：初始 0，每笔变更 +1（递增推进无回退），仅
 *         承载手工调整/对账的冲突检测辅助，不参与防超卖条件更新；</li>
 *     <li>创建即三态清零（初始化无变更语义，不产流水），随后一切数量
 *         变化经变更方法并产流水。</li>
 * </ol>
 * 流水持久化形态：inventory_log 从表（tenant=shopId，append-only，
 * (order_id, sku_id, type) 幂等键唯一约束，行级删除语义不存在）——流水
 * 由聚合方法内嵌构造、经 {@code InventoryLogRepository.append} 追加，
 * 与聚合保存同事务（编排在用例层）。
 *
 * @author nona9961
 */
public class InventoryItem {

    /**
     * 聚合根 ID（Snowflake，本表独立主键）
     */
    private final Long id;

    /**
     * 归属店铺 ID（tenant=shopId，创建时定型不可变）
     */
    private final Long shopId;

    /**
     * 归属 SKU ID（跨域引用键，创建时定型不可变；本表业务唯一键）
     */
    private final Long skuId;

    /**
     * 可售量（三态之一，恒非负）
     */
    private int available;

    /**
     * 预占量（三态之一，恒非负）
     */
    private int held;

    /**
     * 已售量（三态之一，恒非负）
     */
    private int sold;

    /**
     * 乐观锁版本（初始 0，每笔变更 +1；手工调整/对账冲突检测辅助）
     */
    private int version;

    /**
     * 构造库存聚合（工厂创建与仓储读回共用路径）：三态与版本非负校验
     * 为形状守卫，校验通过即定型。
     *
     * @param id        聚合根 ID（必填）
     * @param shopId    归属店铺 ID（必填）
     * @param skuId     归属 SKU ID（必填）
     * @param available 可售量（读回路径值；创建路径为 0）
     * @param held      预占量（读回路径值；创建路径为 0）
     * @param sold      已售量（读回路径值；创建路径为 0）
     * @param version   乐观锁版本（读回路径值；创建路径为 0，非负）
     */
    public InventoryItem(Long id, Long shopId, Long skuId,
                         int available, int held, int sold, int version) {
        if (id == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code(), "库存 ID 不能为空");
        }
        if (shopId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code(), "库存归属店铺不能为空");
        }
        if (skuId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code(), "库存归属 SKU 不能为空");
        }
        if (available < 0 || held < 0 || sold < 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code(), "库存三态数量必须非负");
        }
        if (version < 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code(), "库存乐观锁版本必须非负");
        }
        this.id = id;
        this.shopId = shopId;
        this.skuId = skuId;
        this.available = available;
        this.held = held;
        this.sold = sold;
        this.version = version;
    }

    /**
     * 预占（订单驱动）：可售减少、预占增加——available ≥ demand 方可
     * 执行（领域前置守卫；并发防线为仓储条件更新，属后续阶段）；同时
     * 构造 PREOCCUPY 流水行（orderId 必填），聚合状态与流水随之同事务
     * 落库（编排在用例层）。
     *
     * @param orderId 订单 ID（必填）
     * @param demand  预占数量（必须为正）
     * @return 待追加的 PREOCCUPY 流水行（before/after 三态快照就位）
     */
    public InventoryLog preoccupy(Long orderId, int demand) {
        if (orderId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "订单驱动预占必须携带订单 ID");
        }
        if (demand <= 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_QUANTITY_INVALID.code(), "预占数量必须为正");
        }
        if (available < demand) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code(), "可售库存不足，无法预占");
        }
        final InventoryLog log = new InventoryLog(IDUtils.generateID(), shopId, skuId,
                InventoryLogType.PREOCCUPY, demand, orderId,
                available, held, sold, available - demand, held + demand, sold, null, null);
        available -= demand;
        held += demand;
        version++;
        return log;
    }

    /**
     * 确认扣减（支付成功驱动）：预占减少、已售增加——不足预占拒绝；
     * 同时构造 CONFIRM 流水行。
     *
     * @param orderId  订单 ID（必填）
     * @param quantity 扣减数量（必须为正）
     * @return 待追加的 CONFIRM 流水行
     */
    public InventoryLog confirmDeduct(Long orderId, int quantity) {
        if (orderId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "订单驱动确认扣减必须携带订单 ID");
        }
        if (quantity <= 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_QUANTITY_INVALID.code(), "扣减数量必须为正");
        }
        if (held < quantity) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code(), "预占库存不足，无法确认扣减");
        }
        final InventoryLog log = new InventoryLog(IDUtils.generateID(), shopId, skuId,
                InventoryLogType.CONFIRM, quantity, orderId,
                available, held, sold, available, held - quantity, sold + quantity, null, null);
        held -= quantity;
        sold += quantity;
        version++;
        return log;
    }

    /**
     * 预占回滚（取消/超时释放）：预占减少、可售增加；同时构造 ROLLBACK
     * 流水行。
     *
     * @param orderId  订单 ID（必填）
     * @param quantity 回滚数量（必须为正）
     * @return 待追加的 ROLLBACK 流水行
     */
    public InventoryLog rollback(Long orderId, int quantity) {
        if (orderId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "订单驱动预占回滚必须携带订单 ID");
        }
        if (quantity <= 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_QUANTITY_INVALID.code(), "回滚数量必须为正");
        }
        if (held < quantity) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code(), "预占库存不足，无法回滚");
        }
        final InventoryLog log = new InventoryLog(IDUtils.generateID(), shopId, skuId,
                InventoryLogType.ROLLBACK, quantity, orderId,
                available, held, sold, available + quantity, held - quantity, sold, null, null);
        held -= quantity;
        available += quantity;
        version++;
        return log;
    }

    /**
     * 手工调整可售（商家操作）：仅可售变动（增减皆可），预占/已售不动，
     * 调整后可售 ≥ 0 否则拒绝；同时构造 MANUAL_ADJUST 流水行（无订单
     * 上下文：orderId 恒空，操作人必填、原因可选——调整语义确认）。
     *
     * @param operator 操作人（认证上下文身份标识，必填）
     * @param delta    可售调整量（带符号：正=增可售、负=减可售，非零）
     * @param reason   调整原因（可选）
     * @return 待追加的 MANUAL_ADJUST 流水行
     */
    public InventoryLog adjust(String operator, int delta, String reason) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "手动调整流水必须携带操作人");
        }
        if (delta == 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_QUANTITY_INVALID.code(), "调整数量必须非零");
        }
        if (available + delta < 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code(), "调整后可售不能为负");
        }
        final InventoryLog log = new InventoryLog(IDUtils.generateID(), shopId, skuId,
                InventoryLogType.MANUAL_ADJUST, delta, null,
                available, held, sold, available + delta, held, sold, operator, reason);
        available += delta;
        version++;
        return log;
    }

    /**
     * 退款回补（未发货退款/发货超时关单：已售减少、可售增加）；同时
     * 构造 REFUND_RESTORE 流水行（orderId 必填，幂等键随订单落地）。
     *
     * @param orderId  订单 ID（必填）
     * @param quantity 回补数量（必须为正）
     * @return 待追加的 REFUND_RESTORE 流水行
     */
    public InventoryLog restoreSold(Long orderId, int quantity) {
        if (orderId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "订单驱动退款回补必须携带订单 ID");
        }
        if (quantity <= 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_QUANTITY_INVALID.code(), "回补数量必须为正");
        }
        if (sold < quantity) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code(), "已售数量不足，无法回补");
        }
        final InventoryLog log = new InventoryLog(IDUtils.generateID(), shopId, skuId,
                InventoryLogType.REFUND_RESTORE, quantity, orderId,
                available, held, sold, available + quantity, held, sold - quantity, null, null);
        sold -= quantity;
        available += quantity;
        version++;
        return log;
    }

    /**
     * 聚合根 ID 访问器。
     *
     * @return 聚合根 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 归属店铺 ID 访问器（装配后不可变，跨店铺归属在类型上不可伪造）。
     *
     * @return 归属店铺 ID
     */
    public Long getShopId() {
        return shopId;
    }

    /**
     * 归属 SKU ID 访问器。
     *
     * @return 归属 SKU ID
     */
    public Long getSkuId() {
        return skuId;
    }

    /**
     * 可售量访问器。
     *
     * @return 可售量
     */
    public int getAvailable() {
        return available;
    }

    /**
     * 预占量访问器。
     *
     * @return 预占量
     */
    public int getHeld() {
        return held;
    }

    /**
     * 已售量访问器。
     *
     * @return 已售量
     */
    public int getSold() {
        return sold;
    }

    /**
     * 乐观锁版本访问器。
     *
     * @return 乐观锁版本
     */
    public int getVersion() {
        return version;
    }
}