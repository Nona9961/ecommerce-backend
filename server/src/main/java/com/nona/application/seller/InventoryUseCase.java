package com.nona.application.seller;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.factory.InventoryItemFactory;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家端库存用例：SKU 库存的显式初始化入口（D-5——创建即三态清零，
 * 经工厂 + 仓储编排落库）。
 * <p>
 * 事务边界：初始化（插入根行）为用例事务内完成——创建库存与后续编排
 * 同事务（all-or-nothing）；变更 + 流水同事务的编排形态（保存聚合 +
 * 追加流水，任一失败整体回滚）同以本类为事务边界（动作编排主体随库存
 * 门面后续阶段落地，本类承载初始化入口）。当前店铺由调用方从认证上下文
 * 定位传入（租户过滤兜底：跨店铺写入被写门禁拒绝）。重复初始化守卫：
 * 按 SKU 业务键查重（已存在 → 业务冲突拒绝），并发重复由
 * inventory_item.sku_id 唯一约束兜底（DB 级防线，模拟并发同时落库）。
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
     * 构造库存用例。
     *
     * @param inventoryItemRepository 库存聚合根仓储
     * @param inventoryItemFactory    库存聚合根工厂
     */
    public InventoryUseCase(InventoryItemRepository inventoryItemRepository,
                            InventoryItemFactory inventoryItemFactory) {
        this.inventoryItemRepository = inventoryItemRepository;
        this.inventoryItemFactory = inventoryItemFactory;
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
}