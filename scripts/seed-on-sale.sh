#!/usr/bin/env bash
# =============================================================================
# WU-49 seed：在售商品统一造数（seed-on-sale，本 WU 交付）
#
# 目的：dev 搜索 0 行现状（fixture 商品 DRAFT）缺在售商品；AcTest/fixture
#   数据面缺统一在售集——本脚本补「在售商品造数」统一数据面。
# 形态（红报告 seed 设计）：
#   店（NORMAL）+ 平台分类（ENABLED）+ 品牌（ENABLED）+ 运费模板（PER_ITEM
#   默认）+ product（ON_SALE）+ SKU（enabled + price=5000 分）+
#   inventory（available≥10）+ product_image（1 行主图）。
# 固定 ID 段 97001+（与 59/60 fixture 97xxx 段分域一致；AcTest 96xxx 段
#   不冲突）；落点 = 测试库 ecommerce_test；幂等清理白名单（从表先于主表）。
#
# 运行前提（宿主面）：
#   - localtunnel 隧道（13306→宿主 3306）已起；mysql 客户端 + 无额外依赖
#   - MYSQL_PWD 经环境变量注入（零落盘零打印；禁硬编码——buyer-fixture
#     同款纪律）；无 mysql 客户端的环境可改走 scripts/test-db-reset.sh
#     同款 java+connector 直连方式（本脚本以 mysql 客户端为标准路径）
#
# 验证面（三路）：
#   ① MySQL 基表四形态直查（status/enabled/available/行数）——本脚本内断言
#   ② CDC poll-until PG 镜像收敛（AcTest 面 AcceptanceDbSupport.pollUntil
#     复用——宿主在 61 链路就绪后以 CdcMirrorChainAcTest 同款口径复核）
#   ③ dev 面 product_search_view 可见性（宿主复核——test 库未部署视图，
#     61 未决 1 口径；dev 库 seed 后直查视图命中本商品，命令见文末提示）
# =============================================================================
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-13306}"
MYSQL_USER="${MYSQL_USER:-ecom_app}"
MYSQL_DB="${MYSQL_DB:-ecommerce_test}"
: "${MYSQL_PWD:?MYSQL_PWD 环境变量必填（零落盘零打印）}"

# seed 固定 ID（幂等清理白名单；与 59/60 fixture 97xxx 段分域）
SEED_SHOP=97001
SEED_CAT=97011
SEED_BRAND=97012
SEED_TEMPLATE=97013
SEED_PRODUCT=97021
SEED_SKU=97022
SEED_INV=97023
SEED_IMG=97024

PASS=0
FAIL=0

say()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
ok()   { PASS=$((PASS+1)); say "PASS: $*"; }
bad()  { FAIL=$((FAIL+1)); say "FAIL: $*"; }

[ -x "$(command -v mysql)" ] || { echo 'mysql 客户端未安装（可改走 test-db-reset 同款 java+connector 方式）' >&2; exit 2; }

MYSQL=("mysql" "-h$MYSQL_HOST" "-P$MYSQL_PORT" "-u$MYSQL_USER" "$MYSQL_DB")
MYSQL_PWD="$MYSQL_PWD" "${MYSQL[@]}" -e 'SELECT 1' >/dev/null 2>&1 \
  || { say 'FAIL: 测试库不可达（隧道/凭证）'; exit 1; }

# sql <语句>：执行并返回（-N 无表头、批量关闭）
sql() { MYSQL_PWD="$MYSQL_PWD" "${MYSQL[@]}" -N -e "$1"; }

say '==== seed-on-sale 开始 ===='

# ---------------------------------------------------------------------------
# 1. 幂等清理（从表先于主表；含库存流水；重跑安全）
# ---------------------------------------------------------------------------
sql "DELETE FROM product_image WHERE id = $SEED_IMG OR product_id = $SEED_PRODUCT"
[ $? -eq 0 ] && ok '幂等清理 product_image' || bad '幂等清理 product_image'
sql "DELETE FROM inventory_log WHERE sku_id = $SEED_SKU"
sql "DELETE FROM inventory_item WHERE id = $SEED_INV OR sku_id = $SEED_SKU"
sql "DELETE FROM product_sku WHERE id = $SEED_SKU OR product_id = $SEED_PRODUCT"
sql "DELETE FROM product WHERE id = $SEED_PRODUCT"
sql "DELETE FROM freight_template WHERE id = $SEED_TEMPLATE OR shop_id = $SEED_SHOP"
sql "DELETE FROM brand WHERE id = $SEED_BRAND"
sql "DELETE FROM platform_category WHERE id = $SEED_CAT"
sql "DELETE FROM shop WHERE id = $SEED_SHOP"
[ $? -eq 0 ] && ok '幂等清理主表行' || bad '幂等清理主表行'

# ---------------------------------------------------------------------------
# 2. 直插造数（固定 ID 段 97001+；UTC 时间字面与部署位自洽）
# ---------------------------------------------------------------------------
NOW=$(date -u '+%Y-%m-%d %H:%M:%S')
sql "INSERT INTO shop (create_time, update_time, id, name, status) VALUES ('$NOW', '$NOW', $SEED_SHOP, 'seed在售店', 'NORMAL')"
sql "INSERT INTO platform_category (create_time, update_time, id, name, status, order_no) VALUES ('$NOW', '$NOW', $SEED_CAT, 'seed平台分类', 'ENABLED', 1)"
sql "INSERT INTO brand (create_time, update_time, id, name, status) VALUES ('$NOW', '$NOW', $SEED_BRAND, 'seed品牌', 'ENABLED')"
sql "INSERT INTO freight_template (create_time, update_time, id, shop_id, tenant_id, name, rule_type, status, is_default, per_item_price) VALUES ('$NOW', '$NOW', $SEED_TEMPLATE, $SEED_SHOP, '$SEED_SHOP', 'seed默认运费', 'PER_ITEM', 'ENABLED', b'1', 300)"
sql "INSERT INTO product (create_time, update_time, id, shop_id, tenant_id, brand_id, category_id, freight_template_id, name, status, description) VALUES ('$NOW', '$NOW', $SEED_PRODUCT, $SEED_SHOP, '$SEED_SHOP', $SEED_BRAND, $SEED_CAT, $SEED_TEMPLATE, 'seed在售毛衣', 'ON_SALE', 'seed 搜索面数据')"
sql "INSERT INTO product_sku (create_time, update_time, id, product_id, tenant_id, enabled, price, spec_hash, spec_summary) VALUES ('$NOW', '$NOW', $SEED_SKU, $SEED_PRODUCT, '$SEED_SHOP', b'1', 5000, 'seed-hash', 'seed默认规格')"
sql "INSERT INTO inventory_item (create_time, update_time, id, sku_id, tenant_id, available, held, sold, version) VALUES ('$NOW', '$NOW', $SEED_INV, $SEED_SKU, '$SEED_SHOP', 10, 0, 0, 0)"
sql "INSERT INTO product_image (create_time, update_time, id, product_id, tenant_id, is_primary, url) VALUES ('$NOW', '$NOW', $SEED_IMG, $SEED_PRODUCT, '$SEED_SHOP', b'1', 'https://seed.example.com/main.jpg')"
[ $? -eq 0 ] && ok 'seed 直插 8 行就位' || bad 'seed 直插失败'

# ---------------------------------------------------------------------------
# 3. 基表四形态断言（status/enabled/available/行数）
# ---------------------------------------------------------------------------
[ "$(sql "SELECT status FROM shop WHERE id = $SEED_SHOP")" = "NORMAL" ] \
  && ok '店 NORMAL' || bad '店 NORMAL'
[ "$(sql "SELECT status FROM platform_category WHERE id = $SEED_CAT")" = "ENABLED" ] \
  && ok '平台分类 ENABLED' || bad '平台分类 ENABLED'
[ "$(sql "SELECT status FROM brand WHERE id = $SEED_BRAND")" = "ENABLED" ] \
  && ok '品牌 ENABLED' || bad '品牌 ENABLED'
[ "$(sql "SELECT status FROM freight_template WHERE id = $SEED_TEMPLATE")" = "ENABLED" ] \
  && ok '运费模板 ENABLED' || bad '运费模板 ENABLED'
[ "$(sql "SELECT status FROM product WHERE id = $SEED_PRODUCT")" = "ON_SALE" ] \
  && ok 'product ON_SALE' || bad 'product ON_SALE'
[ "$(sql "SELECT CONCAT(enabled, '/', price) FROM product_sku WHERE id = $SEED_SKU")" = "1/5000" ] \
  && ok 'SKU enabled + price=5000 分' || bad 'SKU enabled + price=5000 分'
AVAIL=$(sql "SELECT available FROM inventory_item WHERE id = $SEED_INV")
[ "${AVAIL:-0}" -ge 10 ] 2>/dev/null && ok "inventory available=$AVAIL (≥10)" || bad "inventory available=$AVAIL (≥10)"
[ "$(sql "SELECT COUNT(*) FROM product_image WHERE product_id = $SEED_PRODUCT AND is_primary = b'1'")" = "1" ] \
  && ok 'product_image 主图 1 行' || bad 'product_image 主图 1 行'
[ "$(sql "SELECT COUNT(*) FROM product_sku WHERE product_id = $SEED_PRODUCT")" = "1" ] \
  && ok 'SKU 关联 1 行' || bad 'SKU 关联 1 行'

# ---------------------------------------------------------------------------
# 4. 宿主验证提示（CDC 镜像 / dev 搜索视图）
# ---------------------------------------------------------------------------
say '---- 宿主复核项（隧道/凭证就绪后执行） ----'
say '② CDC PG 镜像收敛：CdcMirrorChainAcTest 同款 poll-until 口径'
say '   （宿主以 AcceptanceDbSupport.pollUntil 复用面复核 product_search_view 镜像）'
say '③ dev 面搜索视图可见性（test 库未部署视图，61 未决 1 口径）：'
say "   psql -h127.0.0.1 -p15432 -U ecom_app -d ecommerce -c \"SELECT id, name, price FROM product_search_view WHERE id = $SEED_PRODUCT\""
say '   （dev profile app 在线时：GET /mall/search?keyword=在售毛衣 命中 seed 商品）'

say "==== seed-on-sale 结束：PASS=$PASS FAIL=$FAIL ===="
[ "$FAIL" -eq 0 ]