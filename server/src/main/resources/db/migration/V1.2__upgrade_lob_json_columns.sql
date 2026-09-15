-- =============================================================================
-- V1.2 修正：LONGVARCHAR 对齐——三 JSON 列定稿 longtext
-- =============================================================================
-- 【事由】V1.1 的 text 中间态经实测被 Hibernate validate 拒收：@Lob String 的
--   期望类型 = tinytext (Types#CLOB)，表列 text (Types#LONGVARCHAR) → wrong
--   column type。终局方案 = PO 注解 @Lob → @JdbcTypeCode(SqlTypes.LONGVARCHAR)
--   （Hibernate 6 官方替代，MySQL 方言映射 longtext），本脚本把三列对齐为
--   longtext，validate 期望（longtext）与实际列完全同型。
-- 【清单】product_edit_version.snapshot_json（not null）/ product.pending_draft_json
--   / product.spec_template_json。
-- 【形态】v1 < v1.1 < v1.2 < v2：V1（tinytext，Hibernate 导出原样，checksum
--   不可变）→ V1.1（text 中间态，已应用留档）→ V1.2（longtext 终态）；
--   全新环境顺序执行同样收敛到 longtext。
-- 【红线】同 V1：Flyway clean 永久禁止；本脚本仅 ALTER MODIFY，无 DROP。

ALTER TABLE product_edit_version MODIFY COLUMN snapshot_json longtext not null;
ALTER TABLE product MODIFY COLUMN pending_draft_json longtext;
ALTER TABLE product MODIFY COLUMN spec_template_json longtext;