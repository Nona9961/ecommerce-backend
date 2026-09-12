-- =============================================================================
-- WU-54 Trading Tables: 9-table 交易域映射（order 3 / payment 4 / logistics 2）
-- =============================================================================
-- 【导出途径】同 V1：以 Hibernate 6（MySQL 8.4 方言）schema-generation scripts
--   一次性导出（jakarta.persistence.schema-generation.scripts.action=create，
--   临时运行期参数覆盖，未改动任何配置文件）为真源，手审改造后落地；列序为
--   手审编排（validate 逐列核对表名/列名/类型/nullable，与导出同源保真）。
-- 【手审结论】
--   ① 9 表 = 红阶段 9 个新 PO 映射全集（master_order / sub_order / order_item /
--      payment_order / payment_callback_log / refund_order / refund_callback_log /
--      waybill / waybill_track，表名/列/类型/nullable 与 @Table/@Column 一致）。
--   ② 唯一约束 9（uk_*）+ 索引 7（idx_*），与 PO @UniqueConstraint/@Index 逐条
--      对应；独立显式命名语句（ALTER TABLE ... ADD CONSTRAINT / CREATE INDEX），
--      便于 CDC 与索引审计对齐（D6 清单权威，报告注明 D6 计数行勘误）。
--   ③ 租户表 2 张（sub_order / order_item，TenantScopedBasePO 子类）含
--      tenant_id varchar(64) not null（@TenantId 租户列）；交易域其余 7 表 global。
--   ④ 枚举列 enum(...)（@Enumerated(STRING) 全值导出）；布尔列 bit；金额/数量
--      bigint/integer；order_item 两 JSON 扩展列 longtext（@JdbcTypeCode
--      SqlTypes.LONGVARCHAR，V1.2 定稿终态同型）。
--   ⑤ claimed 位（sub_order / payment_order）为超时引擎 SQL 面列，非空列无 DB
--      DEFAULT：应用侧默认值由 PO 字段初始化（Boolean.FALSE）落地（D11）。
--   ⑥ waybill.in_transit 可空（D7 定案 + 主会话批准 PO 注解修复）：TRUE = 在途
--      活动行（每子单至多一张，uk_waybill_sub_order_in_transit 复合唯一防线）、
--      NULL = 历史行（签收释放锚点，MySQL 唯一索引 NULL 多行放行）、FALSE 永不写。
--   ⑦ 超时 SQL 面三列（sub_order / payment_order 的 timeout_at / timeout_type /
--      claimed）承载超时引擎扫描/认领面（领域实体不承载）——(status, timeout_at)
--      复合索引与接口 javadoc 扫描面一致。
-- 【CDC 白名单契约】本脚本 9 表全部落入补齐后白名单（30 业务表 + cdc_probe）：
--   部署位 4 文件（mysql-source.properties / pg-sink.properties / verify.sh /
--   reset-sync.sh）的 refund_callback_log 补位与 verify.sh SKIP 逻辑修正见
--   绿阶段报告；生效命令 = 用户宿主执行 reset-sync.sh 或重启 connect。
-- 【版本序】v1 < v1.1 < v1.2 < v2（Flyway 数值比较，与已应用脚本无冲突）；
--   后续接线 WU 用 V3 起命名。
-- 【红线】Flyway clean 永久禁止：本库是 CDC（Debezium）源库，clean 清空业务表即
--   炸整条 binlog 同步链路；任何脚本/命令不得执行 flyway clean 或手工
--   DROP / DROP DATABASE（清理一律走 scripts/test-db-reset.sh 数据级 DELETE）。

-- =============================================================================
-- order 域（3 表）：master_order / sub_order / order_item
-- =============================================================================

-- 主订单聚合根主表（global，买家维度）：订单号唯一；地址六列 + 金额四列为
-- 快照冗余列（B7.6 创建时定型）；status 为派生态（子单投影派生刷新）。
CREATE TABLE IF NOT EXISTS master_order (
    id             bigint       not null,
    create_time    datetime(6)  not null,
    update_time    datetime(6)  not null,
    order_no       varchar(64)  not null,
    buyer_id       bigint       not null,
    recipient      varchar(64)  not null,
    phone          varchar(32)  not null,
    province       varchar(32)  not null,
    city           varchar(32)  not null,
    district       varchar(32)  not null,
    detail         varchar(255) not null,
    goods_amount   bigint       not null,
    freight_amount bigint       not null,
    discount       bigint       not null,
    paid_amount    bigint       not null,
    status         enum ('PENDING_PAYMENT','PAID','PARTIALLY_SHIPPED','SHIPPED',
                         'COMPLETED','CANCELLED','REFUNDING','REFUNDED','CLOSED')
                   not null,
    primary key (id)
) engine=InnoDB;

-- 子订单聚合根主表（tenant=shopId）：sub_order_no 唯一；(status, timeout_at)
-- 复合索引为超时引擎扫描面；waybill_id 跨域引用可空（不建外键）。
CREATE TABLE IF NOT EXISTS sub_order (
    id              bigint       not null,
    create_time     datetime(6)  not null,
    update_time     datetime(6)  not null,
    tenant_id       varchar(64)  not null,
    master_order_id bigint       not null,
    shop_id         bigint       not null,
    sub_order_no    varchar(64)  not null,
    recipient       varchar(64)  not null,
    phone           varchar(32)  not null,
    province        varchar(32)  not null,
    city            varchar(32)  not null,
    district        varchar(32)  not null,
    detail          varchar(255) not null,
    goods_amount    bigint       not null,
    freight_amount  bigint       not null,
    discount        bigint       not null,
    paid_amount     bigint       not null,
    status          enum ('PENDING_PAYMENT','PAID','SHIPPED','COMPLETED',
                          'CANCELLED','REFUNDING','REFUNDED','CLOSED')
                    not null,
    waybill_id      bigint,
    timeout_at      datetime(6),
    timeout_type    enum ('ORDER_PAY','ORDER_SHIP','ORDER_RECEIVE'),
    claimed         bit          not null,
    primary key (id)
) engine=InnoDB;

-- 订单项从表（tenant=shopId，随主表）：(sub_order_id, sku_id) 结构唯一；
-- spec_attributes / custom_attributes 为 JSON 扩展列（longtext，可空）。
CREATE TABLE IF NOT EXISTS order_item (
    id               bigint        not null,
    create_time      datetime(6)   not null,
    update_time      datetime(6)   not null,
    tenant_id        varchar(64)   not null,
    sub_order_id     bigint        not null,
    product_id       bigint        not null,
    sku_id           bigint        not null,
    product_name     varchar(255)  not null,
    unit_price       bigint        not null,
    quantity         integer       not null,
    subtotal         bigint        not null,
    main_image_url   varchar(512),
    spec_summary     varchar(255),
    spec_attributes  longtext,
    custom_attributes longtext,
    primary key (id)
) engine=InnoDB;

-- -----------------------------------------------------------------------------
-- order 域：唯一约束 + 索引（显式命名）
-- -----------------------------------------------------------------------------
ALTER TABLE master_order ADD CONSTRAINT uk_master_order_order_no UNIQUE (order_no);
ALTER TABLE sub_order ADD CONSTRAINT uk_sub_order_sub_order_no UNIQUE (sub_order_no);
CREATE INDEX idx_sub_order_status_timeout ON sub_order (status, timeout_at);
CREATE INDEX idx_sub_order_shop ON sub_order (shop_id);
ALTER TABLE order_item ADD CONSTRAINT uk_order_item_sub_sku UNIQUE (sub_order_id, sku_id);
CREATE INDEX idx_order_item_sub_order ON order_item (sub_order_id);

-- =============================================================================
-- payment 域（4 表）：payment_order / payment_callback_log / refund_order /
--   refund_callback_log
-- =============================================================================

-- 支付单聚合根主表（global，买家维度）：pay_no 唯一 / order_id 一对一唯一 /
-- channel_txn_no 唯一（MySQL 唯一索引 NULL 多行放行 = 未回调态）；
-- (status, timeout_at) 复合索引为超时引擎扫描面。
CREATE TABLE IF NOT EXISTS payment_order (
    id             bigint       not null,
    create_time    datetime(6)  not null,
    update_time    datetime(6)  not null,
    pay_no         varchar(64)  not null,
    order_id       bigint       not null,
    amount         bigint       not null,
    channel        varchar(32)  not null,
    timeout_at     datetime(6),
    status         enum ('PENDING_PAYMENT','PAID','FAILED','CLOSED') not null,
    channel_txn_no varchar(64),
    timeout_type   enum ('ORDER_PAY','ORDER_SHIP','ORDER_RECEIVE'),
    claimed        bit          not null,
    primary key (id)
) engine=InnoDB;

-- 支付回调留痕从表（global，append-only）：以 payment_order_id（rootId）归主表；
-- occurred_at 为收到时间（UTC 字面）；refund_no 可空（PAY 回调不携带）。
CREATE TABLE IF NOT EXISTS payment_callback_log (
    id               bigint        not null,
    create_time      datetime(6)   not null,
    update_time      datetime(6)   not null,
    payment_order_id bigint        not null,
    callback_type    enum ('PAY','REFUND') not null,
    pay_no           varchar(64)   not null,
    refund_no        varchar(64),
    result           enum ('SUCCESS','FAIL') not null,
    channel_txn_no   varchar(64)   not null,
    amount_cents     bigint        not null,
    occurred_at      datetime(6)   not null,
    primary key (id)
) engine=InnoDB;

-- 退款单聚合根主表（global，买家维度）：refund_no 唯一 / sub_order_id 唯一
-- （一子单一退款单）；channel_refund_txn_no 不建唯一约束（FAILED 重试覆盖更新
-- 流水，唯一约束会拦覆盖）。
CREATE TABLE IF NOT EXISTS refund_order (
    id                    bigint       not null,
    create_time           datetime(6)  not null,
    update_time           datetime(6)  not null,
    refund_no             varchar(64)  not null,
    pay_no                varchar(64)  not null,
    sub_order_id          bigint       not null,
    amount                bigint       not null,
    shipped_at_apply      bit          not null,
    reason                varchar(255),
    status                enum ('PENDING','SUCCEEDED','FAILED') not null,
    channel_refund_txn_no varchar(64),
    primary key (id)
) engine=InnoDB;

-- 退款回调留痕从表（global，append-only）：以 refund_order_id（rootId）归主表；
-- 与支付留痕同构（六字段 + occurred_at UTC 字面）。
CREATE TABLE IF NOT EXISTS refund_callback_log (
    id               bigint        not null,
    create_time      datetime(6)   not null,
    update_time      datetime(6)   not null,
    refund_order_id  bigint        not null,
    callback_type    enum ('PAY','REFUND') not null,
    pay_no           varchar(64)   not null,
    refund_no        varchar(64),
    result           enum ('SUCCESS','FAIL') not null,
    channel_txn_no   varchar(64)   not null,
    amount_cents     bigint        not null,
    occurred_at      datetime(6)   not null,
    primary key (id)
) engine=InnoDB;

-- -----------------------------------------------------------------------------
-- payment 域：唯一约束 + 索引（显式命名）
-- -----------------------------------------------------------------------------
ALTER TABLE payment_order ADD CONSTRAINT uk_payment_order_pay_no UNIQUE (pay_no);
ALTER TABLE payment_order ADD CONSTRAINT uk_payment_order_order_id UNIQUE (order_id);
ALTER TABLE payment_order ADD CONSTRAINT uk_payment_order_channel_txn_no
    UNIQUE (channel_txn_no);
CREATE INDEX idx_payment_order_status_timeout ON payment_order (status, timeout_at);
CREATE INDEX idx_payment_callback_log_payment_order
    ON payment_callback_log (payment_order_id);
ALTER TABLE refund_order ADD CONSTRAINT uk_refund_order_refund_no UNIQUE (refund_no);
ALTER TABLE refund_order ADD CONSTRAINT uk_refund_order_sub_order_id
    UNIQUE (sub_order_id);
CREATE INDEX idx_refund_callback_log_refund_order ON refund_callback_log (refund_order_id);

-- =============================================================================
-- logistics 域（2 表）：waybill / waybill_track
-- =============================================================================

-- 运单聚合根主表（global，租户中立）：(sub_order_id, in_transit) 复合唯一承载
-- 「一子单一在途」DB 防线（D7）——TRUE=在途活动行（每子单至多一张）、NULL=
-- 历史行（签收释放锚点，NULL 多行并存，子单可再次发货）、FALSE 永不写；
-- in_transit 由转换器按状态派生写入（!= DELIVERED → TRUE / 签收 → NULL）。
CREATE TABLE IF NOT EXISTS waybill (
    id          bigint       not null,
    create_time datetime(6)  not null,
    update_time datetime(6)  not null,
    sub_order_id bigint      not null,
    company     varchar(64)  not null,
    tracking_no varchar(64)  not null,
    status      enum ('PENDING_SHIPMENT','SHIPPED','IN_TRANSIT','DELIVERED')
                not null,
    in_transit  bit,
    primary key (id)
) engine=InnoDB;

-- 运单轨迹从表（global，append-only）：以 waybill_id（rootId）归主表；
-- occurred_at 为领域 LocalDateTime 直映射（simulator 本机时钟语义）。
CREATE TABLE IF NOT EXISTS waybill_track (
    id          bigint       not null,
    create_time datetime(6)  not null,
    update_time datetime(6)  not null,
    waybill_id  bigint       not null,
    status      enum ('PENDING_SHIPMENT','SHIPPED','IN_TRANSIT','DELIVERED')
                not null,
    occurred_at datetime(6)  not null,
    description varchar(255),
    primary key (id)
) engine=InnoDB;

-- -----------------------------------------------------------------------------
-- logistics 域：唯一约束 + 索引（显式命名）
-- -----------------------------------------------------------------------------
ALTER TABLE waybill ADD CONSTRAINT uk_waybill_sub_order_in_transit
    UNIQUE (sub_order_id, in_transit);
CREATE INDEX idx_waybill_track_waybill ON waybill_track (waybill_id);