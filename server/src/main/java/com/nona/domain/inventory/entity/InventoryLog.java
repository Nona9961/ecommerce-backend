package com.nona.domain.inventory.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 库存流水行（inventory_log 从表行，append-only 不可变实体）：InventoryItem
 * 聚合审计流的载体——每笔库存变更必产一行流水（类型/数量/订单上下文/
 * 变动前后三态快照），「不可追踪的库存变更不可能」落在本实体形态上：
 * <ol>
 *     <li><b>不可变</b>：无任何 setter，行内容构造时一次性定型（追加后
 *         永不改写/删除，行级删除语义不存在）；</li>
 *     <li><b>零变更不可能</b>：delta 必须非零——无变更不产流水；</li>
 *     <li><b>方向由类型界定</b>：订单驱动型（预占/确认/回滚/回补）delta
 *         恒为正，语义方向由 {@link InventoryLogType} 唯一表达；手动调整
 *         型 delta 带符号（正=增可售、负=减可售）；</li>
 *     <li><b>前后快照</b>：before/after 各为三态全量快照（可售/预占/已售），
 *         任意一行流水可独立复现该 SKU 该时点的库存全貌，审计无需跨行拼接；</li>
 *     <li><b>算术自洽</b>：after 必须等于 before 应用 (type, delta) 的
 *         结果——自相矛盾的流水行无审计意义，构造路径即拒绝；</li>
 *     <li><b>上下文形态与类型一致</b>：订单驱动型 orderId 必填；手动调整
 *         型 orderId 恒空、操作人必填（调整语义确认）；</li>
 *     <li>三态快照各态非负。</li>
 * </ol>
 * 持久化形态：inventory_log 表（tenant=shopId），幂等键 (order_id,
 * sku_id, type) 唯一约束——同一订单同一 SKU 的同一类型变动只允许一次
 * （重复请求被 DB 拒绝，防重复扣减/回滚）。流水由聚合变更方法内嵌构造
 * （收敛点：InventoryItem 变更方法返回本实体），用例层与聚合状态保存同
 * 事务追加落库。创建时间承载行产生时间（create_time 审计列，新建路径
 * 尚未落库时为 null）。
 *
 * @author nona9961
 */
public class InventoryLog {

    /**
     * 行 ID（Snowflake，本表独立主键）
     */
    private final Long id;

    /**
     * 归属店铺 ID（tenant=shopId，创建时定型不可变）
     */
    private final Long shopId;

    /**
     * 归属 SKU ID（跨域引用键，创建时定型不可变）
     */
    private final Long skuId;

    /**
     * 流水类型（方向/上下文形态语义见 {@link InventoryLogType}）
     */
    private final InventoryLogType type;

    /**
     * 变动数量（非零；订单驱动型恒为正，手动调整型带符号）
     */
    private final int delta;

    /**
     * 订单 ID（订单驱动型必填；手动调整型恒为 null）
     */
    private final Long orderId;

    /**
     * 变动前可售量
     */
    private final int beforeAvailable;

    /**
     * 变动前预占量
     */
    private final int beforeHeld;

    /**
     * 变动前已售量
     */
    private final int beforeSold;

    /**
     * 变动后可售量
     */
    private final int afterAvailable;

    /**
     * 变动后预占量
     */
    private final int afterHeld;

    /**
     * 变动后已售量
     */
    private final int afterSold;

    /**
     * 操作人（手动调整型必填非空；订单驱动型由订单引擎驱动，可空）
     */
    private final String operator;

    /**
     * 调整原因（可空——调整语义确认：原因可选不强制）
     */
    private final String reason;

    /**
     * 行产生时间（持久化审计填充；新建路径尚未落库时为 null）
     */
    private final LocalDateTime createdAt;

    /**
     * 构造流水行（新建形态，产生时间待审计填充）。
     *
     * @param id            行 ID（必填）
     * @param shopId        归属店铺 ID（必填）
     * @param skuId         归属 SKU ID（必填）
     * @param type          流水类型（必填）
     * @param delta         变动数量（非零；手动调整型带符号，订单驱动型恒正）
     * @param orderId       订单 ID（订单驱动型必填；手动调整型必须为空）
     * @param beforeAvailable 变动前可售量（非负）
     * @param beforeHeld      变动前预占量（非负）
     * @param beforeSold      变动前已售量（非负）
     * @param afterAvailable  变动后可售量（非负）
     * @param afterHeld       变动后预占量（非负）
     * @param afterSold       变动后已售量（非负）
     * @param operator      操作人（手动调整型必填非空；订单驱动型可空）
     * @param reason        调整原因（可选）
     */
    public InventoryLog(Long id, Long shopId, Long skuId, InventoryLogType type,
                        int delta, Long orderId,
                        int beforeAvailable, int beforeHeld, int beforeSold,
                        int afterAvailable, int afterHeld, int afterSold,
                        String operator, String reason) {
        this(id, shopId, skuId, type, delta, orderId,
                beforeAvailable, beforeHeld, beforeSold,
                afterAvailable, afterHeld, afterSold,
                operator, reason, null);
    }

    /**
     * 构造流水行（完整形态，createdAt 为持久化读回值）：校验语义与
     * {@link #InventoryLog(Long, Long, Long, InventoryLogType, int, Long, int, int, int, int, int, int, String, String)}
     * 一致。
     *
     * @param createdAt 行产生时间（读回路径由审计列填充；null=新建尚未落库）
     */
    public InventoryLog(Long id, Long shopId, Long skuId, InventoryLogType type,
                        int delta, Long orderId,
                        int beforeAvailable, int beforeHeld, int beforeSold,
                        int afterAvailable, int afterHeld, int afterSold,
                        String operator, String reason, LocalDateTime createdAt) {
        if (id == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "流水行 ID 不能为空");
        }
        if (shopId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "流水归属店铺不能为空");
        }
        if (skuId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "流水归属 SKU 不能为空");
        }
        if (type == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "流水类型不能为空");
        }
        if (delta == 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "零变更不产生流水");
        }
        if (type != InventoryLogType.MANUAL_ADJUST && delta < 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(),
                    "订单驱动型流水 delta 必须为正（方向由类型界定）");
        }
        if (beforeAvailable < 0 || beforeHeld < 0 || beforeSold < 0
                || afterAvailable < 0 || afterHeld < 0 || afterSold < 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "流水前后快照各态必须非负");
        }
        final boolean orderDriven = type != InventoryLogType.MANUAL_ADJUST;
        if (orderDriven && orderId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "订单驱动型流水必须携带订单 ID");
        }
        if (!orderDriven && orderId != null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "手动调整型流水不携带订单 ID");
        }
        if (!orderDriven && (operator == null || operator.isBlank())) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(), "手动调整流水必须携带操作人");
        }
        assertArithmeticConsistent(type, delta,
                beforeAvailable, beforeHeld, beforeSold,
                afterAvailable, afterHeld, afterSold);
        this.id = id;
        this.shopId = shopId;
        this.skuId = skuId;
        this.type = type;
        this.delta = delta;
        this.orderId = orderId;
        this.beforeAvailable = beforeAvailable;
        this.beforeHeld = beforeHeld;
        this.beforeSold = beforeSold;
        this.afterAvailable = afterAvailable;
        this.afterHeld = afterHeld;
        this.afterSold = afterSold;
        this.operator = operator;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    /**
     * 流水算术一致性守卫：after 必须等于 before 应用 (type, delta) 的
     * 结果（订单驱动型方向语义见 {@link InventoryLogType}；手动调整型仅
     * 可售变动、delta 带符号）。
     *
     * @param type            流水类型
     * @param delta           变动数量
     * @param beforeAvailable 变动前可售量
     * @param beforeHeld      变动前预占量
     * @param beforeSold      变动前已售量
     * @param afterAvailable  变动后可售量
     * @param afterHeld       变动后预占量
     * @param afterSold       变动后已售量
     */
    private void assertArithmeticConsistent(InventoryLogType type, int delta,
                                            int beforeAvailable, int beforeHeld, int beforeSold,
                                            int afterAvailable, int afterHeld, int afterSold) {
        final boolean consistent = switch (type) {
            case PREOCCUPY ->
                    afterAvailable == beforeAvailable - delta
                            && afterHeld == beforeHeld + delta
                            && afterSold == beforeSold;
            case CONFIRM ->
                    afterHeld == beforeHeld - delta
                            && afterSold == beforeSold + delta
                            && afterAvailable == beforeAvailable;
            case ROLLBACK ->
                    afterHeld == beforeHeld - delta
                            && afterAvailable == beforeAvailable + delta
                            && afterSold == beforeSold;
            case MANUAL_ADJUST ->
                    afterAvailable == beforeAvailable + delta
                            && afterHeld == beforeHeld
                            && afterSold == beforeSold;
            case REFUND_RESTORE ->
                    afterSold == beforeSold - delta
                            && afterAvailable == beforeAvailable + delta
                            && afterHeld == beforeHeld;
        };
        if (!consistent) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(),
                    "流水前后快照与 (类型, 数量) 算术不一致");
        }
    }

    /**
     * 行 ID 访问器。
     *
     * @return 行 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 归属店铺 ID 访问器。
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
     * 流水类型访问器。
     *
     * @return 流水类型
     */
    public InventoryLogType getType() {
        return type;
    }

    /**
     * 变动数量访问器（非零；订单驱动型恒正，手动调整型带符号）。
     *
     * @return 变动数量
     */
    public int getDelta() {
        return delta;
    }

    /**
     * 订单 ID 访问器（手动调整型恒为 null）。
     *
     * @return 订单 ID
     */
    public Long getOrderId() {
        return orderId;
    }

    /**
     * 变动前可售量访问器。
     *
     * @return 变动前可售量
     */
    public int getBeforeAvailable() {
        return beforeAvailable;
    }

    /**
     * 变动前预占量访问器。
     *
     * @return 变动前预占量
     */
    public int getBeforeHeld() {
        return beforeHeld;
    }

    /**
     * 变动前已售量访问器。
     *
     * @return 变动前已售量
     */
    public int getBeforeSold() {
        return beforeSold;
    }

    /**
     * 变动后可售量访问器。
     *
     * @return 变动后可售量
     */
    public int getAfterAvailable() {
        return afterAvailable;
    }

    /**
     * 变动后预占量访问器。
     *
     * @return 变动后预占量
     */
    public int getAfterHeld() {
        return afterHeld;
    }

    /**
     * 变动后已售量访问器。
     *
     * @return 变动后已售量
     */
    public int getAfterSold() {
        return afterSold;
    }

    /**
     * 操作人访问器（手动调整型必填；订单驱动型可空）。
     *
     * @return 操作人
     */
    public String getOperator() {
        return operator;
    }

    /**
     * 调整原因访问器（可空）。
     *
     * @return 调整原因
     */
    public String getReason() {
        return reason;
    }

    /**
     * 行产生时间访问器（新建尚未落库时为 null）。
     *
     * @return 行产生时间
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 幂等键相等语义（(orderId, skuId, type) 三元组）：append-only 行
     * 集合内同键仅允许一行（DB 唯一约束兜底），供审计侧幂等核对使用。
     *
     * @param other 另一流水行
     * @return 幂等键相同返回 true
     */
    public boolean sameIdempotencyKey(InventoryLog other) {
        return Objects.equals(this.orderId, other.orderId)
                && Objects.equals(this.skuId, other.skuId)
                && this.type == other.type;
    }
}