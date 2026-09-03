package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.EditVersionTriggerType;
import com.nona.domain.catalog.entity.ProductEditVersion;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

/**
 * 商品编辑版本工厂：版本行创建的入口（行 ID 生成 + 触发类型定型）。
 * <p>
 * 触发类型定型在工厂（创建路径不得直接摊开枚举值——EDIT / ROLLBACK /
 * REVIEW_PASS / REJECT 的形状不同，分别以独立工厂方法表达）；版本号
 * （MAX+1 分配）与快照（序列化）由用例层编排提供，工厂不承担业务语义。
 * 字段校验（版本号为正/快照与操作人非空/归属商品必填，审核结论行另含
 * 原因与触发类型一致性）由实体构造路径承载。
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

    /**
     * 创建审核通过结论版本（trigger_type=REVIEW_PASS）：审核通过时追加
     * 一行——快照为通过后的生效内容（待审草稿覆盖正式内容后的当前内容，
     * 内容留痕 + 回滚素材双重语义）；操作人为审核人（认证上下文身份）。
     *
     * @param productId    归属商品 ID（必填非空）
     * @param versionNo    版本号（同商品内 MAX+1 分配值）
     * @param snapshotJson 通过后的生效内容全量快照 JSON（必填非空）
     * @param operator     审核人身份标识（认证上下文，必填非空）
     * @return 新建审核通过结论版本（审核结论行构造由实体路径校验）
     */
    public ProductEditVersion createReviewPass(Long productId, int versionNo,
                                               String snapshotJson, String operator) {
        return new ProductEditVersion(IDUtils.generateID(), productId, versionNo,
                snapshotJson, operator, EditVersionTriggerType.REVIEW_PASS, (String) null);
    }

    /**
     * 创建审核驳回结论版本（trigger_type=REJECT）：审核驳回时追加一行——
     * 快照为驳回时刻的生效内容（驳回不改变生效内容）；操作人为审核人；
     * 驳回原因随结论行承载（商家据此修改重提）。
     *
     * @param productId    归属商品 ID（必填非空）
     * @param versionNo    版本号（同商品内 MAX+1 分配值）
     * @param snapshotJson 驳回时刻生效内容全量快照 JSON（必填非空）
     * @param operator     审核人身份标识（认证上下文，必填非空）
     * @param reason       驳回原因（必填非空）
     * @return 新建审核驳回结论版本（审核结论行构造由实体路径校验）
     */
    public ProductEditVersion createReject(Long productId, int versionNo,
                                           String snapshotJson, String operator, String reason) {
        return new ProductEditVersion(IDUtils.generateID(), productId, versionNo,
                snapshotJson, operator, EditVersionTriggerType.REJECT, reason);
    }
}