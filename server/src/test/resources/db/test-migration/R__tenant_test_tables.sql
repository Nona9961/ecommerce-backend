-- =============================================================================
-- 测试支撑表（WU-53 绿阶段补）：租户隔离契约测试专用 @Entity 的表映射
--   test_global_note（BasePO：无租户列）/ test_tenant_note（TenantScopedBasePO：
--   tenant_id varchar(64) not null）
-- =============================================================================
-- 【使用面】仅 test profile：application-test.yml 的 spring.flyway.locations
--   追加 classpath:db/test-migration，生产/dev locations（classpath:db/migration）
--   不含本目录——两张测试表永不进入生产迁移面（V1 基线契约不受影响）。
-- 【形态】Repeatable Migration（R__ 前缀）：IF NOT EXISTS 幂等，checksum 变更
--   时自动重放；不占版本号，不与未来 V2+ 编号冲突。
-- 【红线】同 V1：Flyway clean 永久禁止；本脚本不含任何 DROP / DROP DATABASE。
--   列/类型与 TestGlobalNotePO / TestTenantNotePO 注解对齐（validate 校验面）：
--   id bigint not null PK + create_time/update_time datetime(6) not null（JPA
--   auditing 应用侧赋值，无 DB DEFAULT）+ content varchar(255) 可空（默认长度）。

CREATE TABLE IF NOT EXISTS test_global_note (
    create_time datetime(6) not null,
    id          bigint      not null,
    update_time datetime(6) not null,
    content     varchar(255),
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS test_tenant_note (
    create_time datetime(6) not null,
    id          bigint      not null,
    update_time datetime(6) not null,
    tenant_id   varchar(64) not null,
    content     varchar(255),
    primary key (id)
) engine=InnoDB;