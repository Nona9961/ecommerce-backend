package com.nona.domain.catalog.entity;

/**
 * 商品编辑版本触发类型（product_edit_version 表 trigger_type 列取值）。
 * <p>
 * 本期实现 EDIT（保存留痕）与 ROLLBACK（回滚生成新版本）；REVIEW_PASS /
 * REJECT 为审核流预留枚举值（审核结论承载于版本行的挂点已冻结，
 * 审核状态机与分流属后续阶段，本期不写任何产生路径）。
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
     * 审核通过（属后续阶段：审核结论落版本行的预留值）。
     */
    REVIEW_PASS,

    /**
     * 审核驳回（属后续阶段：驳回结论落版本行的预留值）。
     */
    REJECT
}