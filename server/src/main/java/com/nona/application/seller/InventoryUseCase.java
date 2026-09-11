package com.nona.application.seller;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.factory.InventoryItemFactory;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import com.nona.domain.inventory.repo.InventoryLogRepository;
import com.nona.domain.inventory.service.InventoryEventRouter;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.replica.LastWriteMarker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家端库存用例：SKU 库存的显式初始化入口（创建即三态清零，
 * 经工厂 + 仓储编排落库）与手工调整可售入口（商家操作：仅可售变动、
 * 调整后非负、每笔调整必有流水）。
 * <p>
 * 事务边界：初始化（插入根行）与调整（条件更新 + 流水 + 事件判定）均为
 * 用例事务内完成——任一失败整体回滚；变更 + 流水同事务的编排形态（保存
 * 聚合 + 追加流水，任一失败整体回滚）同以本类为事务边界。当前店铺由
 * 调用方从认证上下文定位传入（租户过滤兜底：跨店铺写入被写门禁拒绝；
 * 调整路径的跨店铺读取按不存在拒绝）。重复初始化守卫：按 SKU 业务键
 * 查重（已存在 → 业务冲突拒绝），并发重复由 inventory_item.sku_id 唯一
 * 约束兜底（DB 级防线）。
 *
 * @author nona9961
 */
@Service
public class InventoryUseCase {

    /**
     * 库存聚合根仓储
     */
    private final InventoryItemRepository inventoryItemRepository;

    /**
     * 库存聚合根工厂（ID 生成 + 三态清零定型）
     */
    private final InventoryItemFactory inventoryItemFactory;

    /**
     * 库存流水仓储（调整流水追加）
     */
    private final InventoryLogRepository inventoryLogRepository;

    /**
     * 售罄/恢复事件统一触发点（调整路径接入）
     */
    private final InventoryEventRouter inventoryEventRouter;

    /**
     * 写后自读窗口埋点（库存调整影响搜索有货态，落库后标记操作人）
     */
    private final LastWriteMarker lastWriteMarker;

    /**
     * 构造库存用例。
     *
     * @param inventoryItemRepository 库存聚合根仓储
     * @param inventoryItemFactory    库存聚合根工厂
     * @param inventoryLogRepository  库存流水仓储
     * @param inventoryEventRouter    事件统一触发点
     * @param lastWriteMarker         写后窗口埋点（库存变更后标记操作人）
     */
    public InventoryUseCase(InventoryItemRepository inventoryItemRepository,
                            InventoryItemFactory inventoryItemFactory,
                            InventoryLogRepository inventoryLogRepository,
                            InventoryEventRouter inventoryEventRouter,
                            LastWriteMarker lastWriteMarker) {
        this.inventoryItemRepository = inventoryItemRepository;
        this.inventoryItemFactory = inventoryItemFactory;
        this.inventoryLogRepository = inventoryLogRepository;
        this.inventoryEventRouter = inventoryEventRouter;
        this.lastWriteMarker = lastWriteMarker;
    }

    /**
     * 初始化 SKU 库存（创建即三态清零：可售/预占/已售 0、乐观锁版本 0，
     * 无变更语义不产流水；初始化后可售查询面立即可读）。
     *
     * @param shopId 当前店铺 ID（认证上下文定位，必填）
     * @param skuId  目标 SKU ID（必填）
     * @return 初始化后的库存聚合
     */
    @Transactional
    public InventoryItem initializeStock(Long shopId, Long skuId) {
        if (inventoryItemRepository.getBySkuId(skuId) != null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_ALREADY_EXISTS.code(),
                    "SKU 库存已初始化，重复初始化被拒绝");
        }
        final InventoryItem item = inventoryItemFactory.createInitial(shopId, skuId);
        inventoryItemRepository.save(item);
        return item;
    }

    /**
     * 手工调整可售（商家显式操作，事务编排）：仅可售变动（增减皆可）、
     * 预占/已售不动、调整后非负。编排面：
     * <ol>
     *     <li>操作人必填守卫（fail-closed：认证身份缺失即拒绝——调整
     *         是审计追踪语义，匿名调整无法追溯）；</li>
     *     <li>按 SKU 定位库存行并加载聚合（租户过滤 fail-closed：不存
     *         在或跨店铺按不存在拒绝，归属不泄露）；</li>
     *     <li>仓储条件更新（并发防线：SQL 算术相对更新 + 调整致负条件，
     *         受影响行数 0 即调整致负拒绝——翻译为库存不足业务异常）；</li>
     *     <li>聚合方法做领域前置守卫并内嵌构造 MANUAL_ADJUST 流水
     *         （before/after 三态快照与带符号 delta 在聚合内组装）；</li>
     *     <li>流水 append-only 追加 + 事件统一触发点判定（售罄/恢复）
     *         ——与条件更新同事务（任一失败整体回滚）。</li>
     * </ol>
     *
     * @param shopId   当前店铺 ID（认证上下文定位，必填）
     * @param skuId    目标 SKU ID（必填）
     * @param delta    可售调整量（带符号：正=增可售、负=减可售，非零）
     * @param operator 操作人（认证上下文身份，必填——调整审计语义）
     * @param reason   调整原因（可选）
     * @return 调整后的库存聚合（本请求视角三态与版本）
     */
    @Transactional
    public InventoryItem adjustStock(Long shopId, Long skuId, int delta,
                                    String operator, String reason) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(),
                    "调整操作人缺失，库存调整被拒绝");
        }
        final InventoryItem located = inventoryItemRepository.getBySkuId(skuId);
        if (located == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_NOT_FOUND.code(),
                    "SKU 库存不存在，库存调整被拒绝");
        }
        final InventoryItem item = inventoryItemRepository.getByID(located.getId());
        if (item == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_NOT_FOUND.code(),
                    "SKU 库存不存在，库存调整被拒绝");
        }
        final int affected = inventoryItemRepository.casAdjust(item.getId(), delta);
        if (affected == 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code(),
                    "调整后可售不能为负，库存调整被拒绝");
        }
        final InventoryLog log = item.adjust(operator, delta, reason);
        inventoryLogRepository.append(log);
        inventoryEventRouter.publishIfNeeded(log);
        markOperatorWrite(operator);
        return item;
    }

    /**
     * 写后窗口埋点（操作人账号：认证上下文身份转 Long 打标——库存调整
     * 影响搜索有货态；身份非数字时跳过埋点，降级语义同埋点设施
     * 故障——窗口失效走 PG 读库）。
     *
     * @param operator 操作人（认证上下文身份，已断言非空）
     */
    private void markOperatorWrite(String operator) {
        try {
            lastWriteMarker.markWrite(Long.valueOf(operator));
        } catch (NumberFormatException e) {
        }
    }
}
