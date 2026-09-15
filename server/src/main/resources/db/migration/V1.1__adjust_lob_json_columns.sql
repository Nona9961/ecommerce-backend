-- =============================================================================
-- V1.1 修正（实测发现）：@Lob JSON 列 tinytext → text
-- =============================================================================
-- 【事由】V1 依 Hibernate 6 导出，三个 @Lob String 列映射为 MySQL tinytext
--   （255 字节上限）。实测（MySQL 8.4.8）商品编辑快照/规范模板/待审草稿
--   JSON 均超 255B，写入报 Data truncation（H2 时代 TEXT 不截断故未暴露）。
--   清单：
--     product_edit_version.snapshot_json  not null（编辑版本全量快照）
--     product.pending_draft_json                    （待审草稿）
--     product.spec_template_json                   （SKU 规范模板）
-- 【形态】独立增量迁移（v1.1 > v1 < v2）：不动已应用 V1 文件（checksum 不可变
--   原则）；版本号避开 V2 命名面，编号连续无冲突。
-- 【红线】同 V1：Flyway clean 永久禁止；本脚本仅 ALTER MODIFY，无 DROP。
-- 【validate 面】Hibernate 对 @Lob String 的 JDBC 类型码 = LONGVARCHAR，
--   tinytext/text 同族（实测 validate 通过）。

ALTER TABLE product_edit_version MODIFY COLUMN snapshot_json text not null;
ALTER TABLE product MODIFY COLUMN pending_draft_json text;
ALTER TABLE product MODIFY COLUMN spec_template_json text;