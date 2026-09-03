package com.nona.domain.catalog.entity;

/**
 * 商品编辑版本触发类型（product_edit_version 表 trigger_type 列取值）。
 * <p>
 * EDIT（保存留痕）与 ROLLBACK（回滚生成新版本）为商家编辑路径；
 * REVIEW_PASS / REJECT 为审核结论行（平台审核裁定落版本链：通过行快照 =
 * 通过后的生效内容，驳回行快照 = 驳回时刻生效内容 + 驳回原因）。
 *
 * @author nona9961
 */
public enum EditVersionTriggerType {

    /**
     * 保存留痕：每次用例变更面落库后追加一行（含创建基线与内容修改）。
     */
    EDIT,

    /**
     * 回滚：以历史快照为新版本内容重走保存流程，追加一行回滚版本。
     */
    ROLLBACK,

    /**
     * 审核通过结论：审核通过时追加一行（快照 = 通过后的生效内容）。
     */
    REVIEW_PASS,

    /**
     * 审核驳回结论：审核驳回时追加一行（快照 = 驳回时刻生效内容，
     * 承载驳回原因）。
     */
    REJECT
}