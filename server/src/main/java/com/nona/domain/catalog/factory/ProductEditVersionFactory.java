package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.EditVersionTriggerType;
import com.nona.domain.catalog.entity.ProductEditVersion;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

/**
 * 商品编辑版本工厂：版本行创建的入口（行 ID 生成 + 触发类型定型）。
 * <p>
 * 触达类型定型在工厂（创建路径不得直接摊开枚举值——EDIT 与 ROLLBACK
 * 的形状不同，分别以独立工厂方法表达）；版本号（MAX+1 分配）与快照
 * JSON（序列化）由用例层编排提供，工厂不承担业务语义。字段校验
 * （版本号为正/快照与操作人非空/归属商品必填）由实体构造路径承载。
 *
 * @author nona9961
 */
@Component
public class ProductEditVersionFactory {

    /**
     * 创建保存留痕版本（trigger_type=EDIT）。
     *
     * @param productId    归属商品 ID（必填非空）
     * @param versionNo    版本号（同商品内 MAX+1 分配值）
     * @param snapshotJson 保存时刻聚合全部内容的全量 JSON 快照（必填非空）
     * @param operator     操作人（认证上下文身份标识，必填非空）
     * @return 新建版本记录（编辑器 + 触发类型 EDIT）
     */
    public ProductEditVersion createEdit(Long productId, int versionNo,
                                         String snapshotJson, String operator) {
        return new ProductEditVersion(IDUtils.generateID(), productId, versionNo,
                snapshotJson, operator, EditVersionTriggerType.EDIT);
    }

    /**
     * 创建回滚版本（trigger_type=ROLLBACK）：回滚 = 以历史快照为新版本
     * 内容再走一遍保存流程——新版本行记录触发类型 ROLLBACK，内容与
     * 回滚目标快照一致。
     *
     * @param productId    归属商品 ID（必填非空）
     * @param versionNo    版本号（同商品内 MAX+1 分配值）
     * @param snapshotJson 回滚后的内容快照（与目标版本快照一致，必填非空）
     * @param operator     操作人（认证上下文身份标识，必填非空）
     * @return 新建版本记录（编辑器 + 触发类型 ROLLBACK）
     */
    public ProductEditVersion createRollback(Long productId, int versionNo,
                                             String snapshotJson, String operator) {
        return new ProductEditVersion(IDUtils.generateID(), productId, versionNo,
                snapshotJson, operator, EditVersionTriggerType.ROLLBACK);
    }
}