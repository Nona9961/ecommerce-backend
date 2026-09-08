-- =============================================================================
-- WU-53 Baseline: 24-table PO 映射全集（identity 9 / catalog 11 / inventory 2 / order 2）
-- =============================================================================
-- 【导出途径】本脚本以 Hibernate 6（MySQL 8.4 方言）schema-generation scripts
--   一次性导出（jakarta.persistence.schema-generation.scripts.action=create，
--   临时运行期参数覆盖，未改动任何配置文件）为真源，手审改造后落地。
-- 【手审结论】
--   ① 24 表 = 既有冻结 PO 映射全集（表名/列/类型/nullable 与 @Table/@Column 一致）；
--      探针表 cdc_probe 不入本脚本（部署位自建，Flyway 不管理）。
--   ② 唯一约束 18（uk_*）+ 索引 11（idx_*），与 PO @UniqueConstraint/@Index 逐条对应；
--      独立显式命名语句（ALTER TABLE ... ADD CONSTRAINT / CREATE INDEX），
--      便于 CDC 与索引审计对齐。
--   ③ 表名与 CDC 白名单修订后一致：account / address_book / cart（部署位三处同步见
--      用户执行清单）；role / permission / assignment 直写无引号——MySQL 8.4.8
--      实测为非保留字（红阶段探针闭合 WU-10 遗留）。
--   ④ create_time / update_time / review_time 使用 datetime(6)，无 DB DEFAULT：
--      审计时间由应用侧 JPA auditing（@CreatedDate/@LastModifiedDate）赋值。
--   ⑤ 类型细节同源保真：枚举列 enum(...)（@Enumerated(STRING)）、布尔列 bit
--      （boolean/Boolean）、JSON 快照列 tinytext（@Lob String，体量小设计声明见 PO
--      javadoc）、金额/数量列 bigint/integer（分与件语义）——validate 与导出同源。
--   ⑥ 10 张 tenant-scoped 表（TenantScopedBasePO 子类）含 tenant_id varchar(64)
--      not null（@TenantId 租户列）。
-- 【CDC 白名单契约】修正后白名单 = 29 业务表 + cdc_probe = 30 项；本脚本 24 表
--   全部落入白名单（其余 5 张交易域表 master_order/sub_order/order_item/... 由
--   WU-54 V2 落库后入白名单）。
-- 【红线】Flyway clean 永久禁止：本库是 CDC（Debezium）源库，clean 清空业务表即
--   炸整条 binlog 同步链路；任何脚本/命令不得执行 flyway clean 或手工
--   DROP / DROP DATABASE（清理一律走 scripts/test-db-reset.sh 数据级 DELETE）。

-- =============================================================================
-- identity 域（9 表）
-- =============================================================================

CREATE TABLE IF NOT EXISTS account (
    create_time    datetime(6)  not null,
    id             bigint       not null,
    update_time    datetime(6)  not null,
    password_hash  varchar(64)  not null,
    username       varchar(64)  not null,
    status         enum ('BANNED','NORMAL') not null,
    type           enum ('BUYER','SELLER') not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS account_shop_rel (
    account_id  bigint  not null,
    create_time datetime(6) not null,
    id          bigint  not null,
    shop_id     bigint  not null,
    update_time datetime(6) not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS address (
    is_default  bit          not null,
    book_id     bigint       not null,
    create_time datetime(6)  not null,
    id          bigint       not null,
    update_time datetime(6)  not null,
    city        varchar(32)  not null,
    district    varchar(32)  not null,
    phone       varchar(32)  not null,
    province    varchar(32)  not null,
    recipient   varchar(64)  not null,
    detail      varchar(255) not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS address_book (
    account_id  bigint       not null,
    create_time datetime(6)  not null,
    id          bigint       not null,
    update_time datetime(6)  not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS assignment (
    account_id  bigint       not null,
    create_time datetime(6)  not null,
    id          bigint       not null,
    role_id     bigint       not null,
    update_time datetime(6)  not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS favorite (
    account_id    bigint                not null,
    create_time   datetime(6)           not null,
    id            bigint                not null,
    target_id     bigint                not null,
    update_time   datetime(6)           not null,
    favorite_type enum ('PRODUCT','SHOP') not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS onboarding_application (
    account_id    bigint                     not null,
    create_time   datetime(6)                not null,
    id            bigint                     not null,
    review_time   datetime(6),
    reviewer_id   bigint,
    update_time   datetime(6)                not null,
    contact_phone varchar(32)                not null,
    contact_name  varchar(64)                not null,
    shop_name     varchar(128)               not null,
    reject_reason varchar(512),
    status        enum ('APPROVED','PENDING','REJECTED') not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS permission (
    create_time datetime(6)  not null,
    id          bigint       not null,
    update_time datetime(6)  not null,
    name        varchar(64)  not null,
    code        varchar(128) not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS role (
    create_time datetime(6) not null,
    id          bigint      not null,
    update_time datetime(6) not null,
    code        varchar(64) not null,
    name        varchar(64) not null,
    primary key (id)
) engine=InnoDB;

-- -----------------------------------------------------------------------------
-- identity 域：唯一约束 + 索引（显式命名）
-- -----------------------------------------------------------------------------
ALTER TABLE account ADD CONSTRAINT uk_account_type_username UNIQUE (type, username);
ALTER TABLE account_shop_rel ADD CONSTRAINT uk_account_shop_rel UNIQUE (account_id, shop_id);
CREATE INDEX idx_address_book ON address (book_id);
ALTER TABLE address_book ADD CONSTRAINT uk_address_book_account UNIQUE (account_id);
ALTER TABLE assignment ADD CONSTRAINT uk_assignment_account_role UNIQUE (account_id, role_id);
ALTER TABLE favorite ADD CONSTRAINT uk_favorite_account_type_target UNIQUE (account_id, favorite_type, target_id);
ALTER TABLE onboarding_application ADD CONSTRAINT uk_onboarding_account UNIQUE (account_id);
ALTER TABLE permission ADD CONSTRAINT uk_permission_code UNIQUE (code);
ALTER TABLE role ADD CONSTRAINT uk_role_code UNIQUE (code);

-- =============================================================================
-- catalog 域（11 表）
-- =============================================================================

CREATE TABLE IF NOT EXISTS brand (
    create_time datetime(6) not null,
    id          bigint      not null,
    update_time datetime(6) not null,
    name        varchar(64) not null,
    logo        varchar(512),
    status      enum ('DISABLED','ENABLED') not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS freight_template (
    is_default      bit                           not null,
    base_freight    bigint,
    create_time     datetime(6)                   not null,
    free_threshold  bigint,
    id              bigint                        not null,
    per_item_price  bigint,
    shop_id         bigint                        not null,
    update_time     datetime(6)                   not null,
    name            varchar(64)                   not null,
    tenant_id       varchar(64)                   not null,
    rule_type       enum ('FREE','PER_ITEM','THRESHOLD_FREE') not null,
    status          enum ('DISABLED','ENABLED')   not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS platform_category (
    order_no    integer      not null,
    create_time datetime(6)  not null,
    id          bigint       not null,
    update_time datetime(6)  not null,
    name        varchar(64)  not null,
    status      enum ('DISABLED','ENABLED') not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS product_attribute (
    create_time datetime(6)  not null,
    id          bigint       not null,
    product_id  bigint       not null,
    update_time datetime(6)  not null,
    attr_key    varchar(64)  not null,
    tenant_id   varchar(64)  not null,
    attr_value  varchar(512),
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS product_edit_version (
    version_no    integer                            not null,
    create_time   datetime(6)                        not null,
    id            bigint                             not null,
    product_id    bigint                             not null,
    update_time   datetime(6)                        not null,
    operator      varchar(64)                        not null,
    tenant_id     varchar(64)                        not null,
    review_reason varchar(512),
    snapshot_json tinytext                           not null,
    trigger_type  enum ('EDIT','REJECT','REVIEW_PASS','ROLLBACK') not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS product_image (
    is_primary  bit          not null,
    create_time datetime(6)  not null,
    id          bigint       not null,
    product_id  bigint       not null,
    update_time datetime(6)  not null,
    tenant_id   varchar(64)  not null,
    url         varchar(512) not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS product (
    brand_id           bigint,
    category_id        bigint,
    create_time        datetime(6)  not null,
    freight_template_id bigint,
    id                 bigint       not null,
    shop_id            bigint       not null,
    update_time        datetime(6)  not null,
    tenant_id          varchar(64)  not null,
    name               varchar(128) not null,
    description        varchar(4000),
    pending_draft_json tinytext,
    spec_template_json tinytext,
    status             enum ('DELISTED','DRAFT','ON_SALE','PENDING_REVIEW') not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS product_shop_category_rel (
    create_time     datetime(6) not null,
    id              bigint      not null,
    product_id      bigint      not null,
    shop_category_id bigint     not null,
    update_time     datetime(6) not null,
    tenant_id       varchar(64) not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS shop_category (
    order_no    integer      not null,
    create_time datetime(6)  not null,
    id          bigint       not null,
    shop_id     bigint       not null,
    update_time datetime(6)  not null,
    name        varchar(64)  not null,
    tenant_id   varchar(64)  not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS shop (
    create_time datetime(6)  not null,
    id          bigint       not null,
    update_time datetime(6)  not null,
    name        varchar(128) not null,
    logo        varchar(512),
    description varchar(2000),
    status      enum ('FROZEN','NORMAL') not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS product_sku (
    enabled      bit          not null,
    create_time  datetime(6)  not null,
    id           bigint       not null,
    price        bigint,
    product_id   bigint       not null,
    update_time  datetime(6)  not null,
    spec_hash    varchar(64)  not null,
    tenant_id    varchar(64)  not null,
    spec_summary varchar(512) not null,
    primary key (id)
) engine=InnoDB;

-- -----------------------------------------------------------------------------
-- catalog 域：唯一约束 + 索引（显式命名）
-- -----------------------------------------------------------------------------
ALTER TABLE brand ADD CONSTRAINT uk_brand_name UNIQUE (name);
CREATE INDEX idx_freight_template_shop ON freight_template (shop_id);
ALTER TABLE platform_category ADD CONSTRAINT uk_platform_category_name UNIQUE (name);
ALTER TABLE product_attribute ADD CONSTRAINT uk_product_attribute_key UNIQUE (product_id, attr_key);
CREATE INDEX idx_product_attribute_product ON product_attribute (product_id);
ALTER TABLE product_edit_version ADD CONSTRAINT uk_product_edit_version_no UNIQUE (product_id, version_no);
CREATE INDEX idx_product_edit_version_product ON product_edit_version (product_id);
CREATE INDEX idx_product_image_product ON product_image (product_id);
CREATE INDEX idx_product_shop ON product (shop_id);
ALTER TABLE product_shop_category_rel ADD CONSTRAINT uk_product_shop_category_rel UNIQUE (product_id, shop_category_id);
CREATE INDEX idx_product_shop_category_rel_product ON product_shop_category_rel (product_id);
CREATE INDEX idx_product_sku_product ON product_sku (product_id);
ALTER TABLE product_sku ADD CONSTRAINT uk_product_sku_spec_hash UNIQUE (product_id, spec_hash);
CREATE INDEX idx_shop_category_shop ON shop_category (shop_id);

-- =============================================================================
-- inventory 域（2 表）
-- =============================================================================

CREATE TABLE IF NOT EXISTS inventory_item (
    available   integer      not null,
    held        integer      not null,
    sold        integer      not null,
    version     integer      not null,
    create_time datetime(6)  not null,
    id          bigint       not null,
    sku_id      bigint       not null,
    update_time datetime(6)  not null,
    tenant_id   varchar(64)  not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS inventory_log (
    after_available  integer      not null,
    after_held       integer      not null,
    after_sold       integer      not null,
    before_available integer      not null,
    before_held      integer      not null,
    before_sold      integer      not null,
    delta            integer      not null,
    create_time      datetime(6)  not null,
    id               bigint       not null,
    order_id         bigint,
    sku_id           bigint       not null,
    update_time      datetime(6)  not null,
    operator         varchar(64),
    tenant_id        varchar(64)  not null,
    reason           varchar(512),
    type             enum ('CONFIRM','MANUAL_ADJUST','PREOCCUPY','REFUND_RESTORE','ROLLBACK') not null,
    primary key (id)
) engine=InnoDB;

-- -----------------------------------------------------------------------------
-- inventory 域：唯一约束 + 索引（显式命名）
-- -----------------------------------------------------------------------------
ALTER TABLE inventory_item ADD CONSTRAINT uk_inventory_item_sku UNIQUE (sku_id);
CREATE INDEX idx_inventory_log_sku ON inventory_log (sku_id);
ALTER TABLE inventory_log ADD CONSTRAINT uk_inventory_log_order_sku_type UNIQUE (order_id, sku_id, type);

-- =============================================================================
-- order 域（2 表）
-- =============================================================================

CREATE TABLE IF NOT EXISTS cart (
    buyer_id    bigint       not null,
    create_time datetime(6)  not null,
    id          bigint       not null,
    update_time datetime(6)  not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS cart_item (
    checked     bit           not null,
    quantity    integer       not null,
    buyer_id    bigint        not null,
    cart_id     bigint        not null,
    create_time datetime(6)   not null,
    id          bigint        not null,
    product_id  bigint        not null,
    shop_id     bigint        not null,
    sku_id      bigint        not null,
    update_time datetime(6)   not null,
    shop_name   varchar(255)  not null,
    primary key (id)
) engine=InnoDB;

-- -----------------------------------------------------------------------------
-- order 域：唯一约束 + 索引（显式命名）
-- -----------------------------------------------------------------------------
ALTER TABLE cart ADD CONSTRAINT uk_cart_buyer UNIQUE (buyer_id);
ALTER TABLE cart_item ADD CONSTRAINT uk_cart_item_buyer_sku UNIQUE (buyer_id, sku_id);
CREATE INDEX idx_cart_item_cart ON cart_item (cart_id);