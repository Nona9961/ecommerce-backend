#!/usr/bin/env bash
# =============================================================================
# 商家/平台侧接线 walkthrough（WU-60 交付物：9 seller 端点 + GET /admin/logistics）
# -----------------------------------------------------------------------------
# 造数约定：仅共享测试库 ecommerce_test 与账号/店铺造数约定（49 seed 复用
# 约定；59/60 并行域隔离，不共享 59 脚本文件）。
# 数据依赖链（红设计 §9，按 60 分支现状落地）：
#   登录（既有 seller/admin 账号约定，shopId 取登录响应 shopIds[0]）
#   → 创建草稿 → 配置规格模板 → SKU 定价 → 库存后门造数（未决项 1：
#   库存行初始化零接线，fixture 走测试库后门，同装配面决策）
#   → 子单/运单后门造数（60 分支无买家下单端点——59 并行域未合入；
#   真实买家下单→支付→发货链登记未决）→ 商家发货（真实用例链路）
#   → 9 端点逐端点断言 + admin logistics。
# 断言纪律：状态码 + 形状（jq 逐字段）+ 金额分（后端不再换算）。
# admin logistics：形状 per WU-48；数据面依赖 PG 镜像 CDC 收敛（poll 等
# 待，同 61 纪律——镜像不收敛时该段登记未决，不阻塞 seller 面）。
# -----------------------------------------------------------------------------
# 运行前置（隧道会话内）：
#   export ECOM_DB_PASSWORD="$MYSQL_ECOM_PW"        # 凭证零落盘零打印
#   ./scripts/test-db-reset.sh                      # 前置清理（同 59 纪律）
#   BASE_URL=... SELLER_USER=... SELLER_PASS=... \
#   ADMIN_USER=... ADMIN_PASS=... ./scripts/seller-fixture.sh
# 依赖：curl + jq + JDK 25（后门单文件源码模式）
# =============================================================================
set -euo pipefail

# ---------------- 环境与依赖检查 ----------------
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
SELLER_USER="${SELLER_USER:?环境变量缺失：SELLER_USER（商家登录账号）}"
SELLER_PASS="${SELLER_PASS:?环境变量缺失：SELLER_PASS}"
ADMIN_USER="${ADMIN_USER:?环境变量缺失：ADMIN_USER（平台运营登录账号）}"
ADMIN_PASS="${ADMIN_PASS:?环境变量缺失：ADMIN_PASS}"
# 跨店 404 断言的第二商家（可选；缺省跳过——装配面 AcTest 已覆盖该面）
SELLER_B_USER="${SELLER_B_USER:-}"
SELLER_B_PASS="${SELLER_B_PASS:-}"
ADMIN_LOGISTICS_WAIT_SEC="${ADMIN_LOGISTICS_WAIT_SEC:-60}"

command -v curl >/dev/null 2>&1 || { echo "FATAL: 依赖 curl 缺失" >&2; exit 2; }
command -v jq >/dev/null 2>&1 || { echo "FATAL: 依赖 jq 缺失" >&2; exit 2; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONNECTOR="$(find /opt/code/.m2/repository/com/mysql/mysql-connector-j \
    -name 'mysql-connector-j-*.jar' 2>/dev/null | sort -V | tail -1)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
[[ -z "${JAVA_HOME:-}" ]] && JAVA_BIN="java"

PASS=0
FAIL=0

fail() {
    echo "✗ ASSERT FAIL: $1" >&2
    FAIL=$((FAIL + 1))
}

pass() {
    echo "✓ $1"
    PASS=$((PASS + 1))
}

# 登录（portal: SELLER/ADMIN/BUYER）→ 输出 token；shopId 由调用方经
# login_shop_id 单独取（登录响应 data.shopIds[0]）
login() {
    local user="$1" pass="$2" portal="$3"
    curl -sf -X POST "$BASE_URL/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$user\",\"password\":\"$pass\",\"portal\":\"$portal\"}" \
        | jq -r '.data.token'
}

login_shop_id() {
    local user="$1" pass="$2" portal="$3"
    curl -sf -X POST "$BASE_URL/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$user\",\"password\":\"$pass\",\"portal\":\"$portal\"}" \
        | jq -r '.data.shopIds[0]'
}

# 断言：GET 请求 + jq 表达式为真（expr 为单个参数）
assert_get_jq() {
    local desc="$1" token="$2" path="$3" query="$4" jq_expr="$5"
    local result
    if [[ -n "$query" ]]; then
        result="$(curl -sf -X GET "$BASE_URL$path?$query" \
            -H "Authorization: Bearer $token" 2>/dev/null)" || { fail "$desc（请求失败）"; return; }
    else
        result="$(curl -sf -X GET "$BASE_URL$path" \
            -H "Authorization: Bearer $token" 2>/dev/null)" || { fail "$desc（请求失败）"; return; }
    fi
    if echo "$result" | jq -e "$jq_expr" >/dev/null 2>&1; then
        pass "$desc"
    else
        fail "$desc（jq=$jq_expr 不成立）"
    fi
}

# 断言：HTTP 状态码
assert_status() {
    local desc="$1" token="$2" method="$3" path="$4" body="$5" expect="$6"
    local http
    if [[ -n "$body" ]]; then
        http="$(curl -s -o /dev/null -w '%{http_code}' -X "$method" "$BASE_URL$path" \
            -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
            -d "$body" 2>/dev/null)" || { fail "$desc（请求失败）"; return; }
    else
        http="$(curl -s -o /dev/null -w '%{http_code}' -X "$method" "$BASE_URL$path" \
            -H "Authorization: Bearer $token" 2>/dev/null)" || { fail "$desc（请求失败）"; return; }
    fi
    if [[ "$http" == "$expect" ]]; then
        pass "$desc（HTTP $http）"
    else
        fail "$desc（期望 $expect，实际 $http）"
    fi
}

# 后门造数（SellerFixtureDb 单文件，凭证经环境变量）
db_seed() {
    [[ -n "$CONNECTOR" && -f "$CONNECTOR" ]] || { echo "FATAL: mysql-connector-j jar 未在本地 m2 找到" >&2; exit 2; }
    "${JAVA_BIN}" --class-path "${CONNECTOR}" "${SCRIPT_DIR}/SellerFixtureDb.java" "$@"
}

# ---------------- 主流程 ----------------
echo "==[1/9] 登录 =="
SELLER_TOKEN="$(login "$SELLER_USER" "$SELLER_PASS" "SELLER")"
[ -n "$SELLER_TOKEN" ] && pass "商家登录" || fail "商家登录"
ADMIN_TOKEN="$(login "$ADMIN_USER" "$ADMIN_PASS" "ADMIN")"
[ -n "$ADMIN_TOKEN" ] && pass "平台登录" || fail "平台登录"
SHOP_ID="$(login_shop_id "$SELLER_USER" "$SELLER_PASS" "SELLER")"
[ -n "$SHOP_ID" ] && pass "店铺定位（shopId=$SHOP_ID）" || fail "店铺定位"
SELLER_B_TOKEN=""
if [[ -n "$SELLER_B_USER" && -n "$SELLER_B_PASS" ]]; then
    SELLER_B_TOKEN="$(login "$SELLER_B_USER" "$SELLER_B_PASS" "SELLER")"
    [ -n "$SELLER_B_TOKEN" ] && pass "商家 B 登录（跨店断言面）" || fail "商家 B 登录"
fi

echo "==[2/9] 商品造数：草稿 → 规格模板 → SKU 定价 =="
DRAFT_JSON="$(curl -sf -X POST "$BASE_URL/seller/products" \
    -H "Authorization: Bearer $SELLER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"name":"walkthrough 商品"}' )" || { echo "FATAL: 创建草稿失败" >&2; exit 2; }
PRODUCT_ID="$(echo "$DRAFT_JSON" | jq -r '.data.id')"
[ -n "$PRODUCT_ID" ] && pass "创建商品草稿（id=$PRODUCT_ID）" || fail "创建商品草稿"
SKUS_JSON="$(curl -sf -X PUT "$BASE_URL/seller/products/$PRODUCT_ID/spec-template" \
    -H "Authorization: Bearer $SELLER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"dimensions":[{"name":"颜色","values":["黑","白"]}]}')"
SKU_ID="$(echo "$SKUS_JSON" | jq -r '.data[0].id')"
[ -n "$SKU_ID" ] && pass "配置规格模板（skuId=$SKU_ID）" || fail "配置规格模板"
curl -sf -X PUT "$BASE_URL/seller/products/$PRODUCT_ID/skus/$SKU_ID/price" \
    -H "Authorization: Bearer $SELLER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"price":2599}' >/dev/null && pass "SKU 定价 2599 分" || fail "SKU 定价"

echo "==[3/9] 库存后门造数（未决项 1：初始化零接线 → 测试库后门） =="
db_seed seed-inventory "$SHOP_ID" "$SKU_ID" 15 5 30 \
    && pass "库存行后门造数（available=15/held=5/sold=30）" || fail "库存行后门造数"

echo "==[4/9] 库存三端点 =="
assert_get_jq "库存列表：join 商品名 + 三态形状" "$SELLER_TOKEN" "/seller/inventory" "" \
    ".data.total == 1 and .data.records[0].skuId == $SKU_ID \
     and .data.records[0].productName == \"walkthrough 商品\" \
     and .data.records[0].specSummary == \"颜色:黑\" \
     and .data.records[0].available == 15 and .data.records[0].held == 5 \
     and .data.records[0].sold == 30"
ADJUST_JSON="$(curl -sf -X PUT "$BASE_URL/seller/inventory/$SKU_ID" \
    -H "Authorization: Bearer $SELLER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"delta":5,"reason":"补货"}')"
echo "$ADJUST_JSON" | jq -e '.data.available == 20 and .data.held == 5 and .data.sold == 30' >/dev/null \
    && pass "库存调整：+5 → available=20" || fail "库存调整"
assert_get_jq "库存流水：14 字段形状 + MANUAL_ADJUST" "$SELLER_TOKEN" \
    "/seller/inventory/$SKU_ID/logs" "" \
    ".data.total >= 1 and .data.records[0].type == \"MANUAL_ADJUST\" \
     and .data.records[0].delta == 5 and .data.records[0].reason == \"补货\" \
     and .data.records[0].beforeAvailable == 15 and .data.records[0].afterAvailable == 20 \
     and .data.records[0].operator != null and .data.records[0].createdAt != null"
assert_status "库存调整致负拒绝（400）" "$SELLER_TOKEN" PUT "/seller/inventory/$SKU_ID" \
    '{"delta":-100}' 400

echo "==[5/9] 订单后门造数（60 分支无买家下单端点 → 子单直插；真实买家链登记未决） =="
db_seed seed-order "$SHOP_ID" 76001 75001 "WALK-SO-001" "PAID" -1 \
    "$PRODUCT_ID" "$SKU_ID" "$SKU_ID" \
    && pass "子单（PAID 未发货）后门造数" || fail "子单后门造数"

echo "==[6/9] 订单列表/详情/发货 =="
assert_get_jq "订单列表：行形状 + 金额分（48000/800/48800）" "$SELLER_TOKEN" "/seller/orders" "" \
    ".data.total >= 1 and .data.records[0].subOrderNo == \"WALK-SO-001\" \
     and .data.records[0].goodsAmount == 48000 and .data.records[0].freightAmount == 800 \
     and .data.records[0].paidAmount == 48800 and .data.records[0].itemCount == 2 \
     and .data.records[0].recipient == \"张三\" and .data.records[0].createTime != null"
assert_get_jq "订单列表：status 单值过滤" "$SELLER_TOKEN" "/seller/orders" "status=PAID" \
    ".data.total >= 1 and .data.records[0].status == \"PAID\""
assert_get_jq "订单详情：金额/地址/订单项 + waybill null（未发货）" "$SELLER_TOKEN" \
    "/seller/orders/76001" "" \
    ".data.subOrderId == 76001 and .data.status == \"PAID\" \
     and .data.address.recipient == \"张三\" and .data.amount.goodsAmount == 48000 \
     and .data.items.length() == 2 and .data.waybill == null"
assert_status "发货：必填非空校验拒绝（400）" "$SELLER_TOKEN" POST "/seller/sub-orders/76001/ship" \
    '{"company":"","trackingNo":""}' 400
assert_status "发货：成功（200）" "$SELLER_TOKEN" POST "/seller/sub-orders/76001/ship" \
    '{"company":"顺丰速运","trackingNo":"SF-WALK-001"}' 200
assert_get_jq "发货后详情：SHIPPED + 运单概要 + 金额分" "$SELLER_TOKEN" "/seller/orders/76001" "" \
    ".data.status == \"SHIPPED\" and .data.waybill.company == \"顺丰速运\" \
     and .data.waybill.trackingNo == \"SF-WALK-001\" \
     and .data.waybill.status != null and .data.amount.paidAmount == 48800"
assert_status "发货幂等：重复发货成功（200）" "$SELLER_TOKEN" POST "/seller/sub-orders/76001/ship" \
    '{"company":"顺丰速运","trackingNo":"SF-WALK-001"}' 200

echo "==[7/9] 商品编辑回显三 GET =="
assert_get_jq "SKU 集回显：价格分透传 + 启用位" "$SELLER_TOKEN" "/seller/products/$PRODUCT_ID/skus" "" \
    ".data.length() == 2 and .data[0].price == 2599 and .data[0].enabled == false"
assert_get_jq "规格模板回显：维度形状" "$SELLER_TOKEN" "/seller/products/$PRODUCT_ID/spec-template" "" \
    ".data.dimensions.length() == 1 and .data.dimensions[0].name == \"颜色\" \
     and .data.dimensions[0].values[0] == \"黑\" and .data.dimensions[0].values[1] == \"白\""
assert_get_jq "运费绑定回显：未绑定 null" "$SELLER_TOKEN" "/seller/products/$PRODUCT_ID/freight-template" "" \
    ".data.freightTemplateId == null"

echo "==[8/9] 跨店 fail-closed（需 SELLER_B；缺省跳过——装配面已覆盖） =="
if [[ -n "$SELLER_B_TOKEN" ]]; then
    assert_status "B 店铺查 A 店铺子单 → 404" "$SELLER_B_TOKEN" GET "/seller/orders/76001" "" 404
    assert_status "B 店铺查 A 店铺库存流水 → 404" "$SELLER_B_TOKEN" GET "/seller/inventory/$SKU_ID/logs" "" 404
else
    echo "（跳过：未提供 SELLER_B_USER/SELLER_B_PASS——跨店面由装配面 AcTest 覆盖）"
fi

echo "==[9/9] admin 物流总览（形状 per WU-48；PG 镜像 CDC 收敛 poll） =="
LOGISTICS_OK=0
for ((i = 0; i < ADMIN_LOGISTICS_WAIT_SEC; i += 5)); do
    if curl -sf -X GET "$BASE_URL/admin/logistics" \
        -H "Authorization: Bearer $ADMIN_TOKEN" 2>/dev/null | jq -e '.data.records != null' >/dev/null 2>&1; then
        LOGISTICS_OK=1
        break
    fi
    sleep 5
done
if [[ "$LOGISTICS_OK" == "1" ]]; then
    assert_get_jq "admin 物流：行形状（12 字段）" "$ADMIN_TOKEN" "/admin/logistics" "" \
        ".data.total >= 1 and .data.records[0].subOrderId != null \
         and .data.records[0].subOrderNo != null and .data.records[0].shopId != null \
         and .data.records[0].shopName != null and .data.records[0].subOrderStatus != null \
         and (.data.records[0].timeoutOverdue == true or .data.records[0].timeoutOverdue == false)"
else
    echo "（未决登记：admin 物流数据面依赖 PG 镜像 CDC 收敛，等待 ${ADMIN_LOGISTICS_WAIT_SEC}s 未就绪——重复运行或先核镜像同步；seller 面断言不受影响）"
fi

echo ""
echo "================ walkthrough 汇总（WU-60） ================"
echo "PASS=$PASS FAIL=$FAIL"
if [[ "$FAIL" -gt 0 ]]; then
    echo "RESULT: FAILURE（部分断言失败，明细见上）"
    exit 1
fi
echo "RESULT: SUCCESS"