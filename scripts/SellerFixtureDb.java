import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 商家/平台侧 walkthrough 数据库后门（seller-fixture 自带文件，
 * 仅共享测试库与账号/店铺造数约定）。
 * <p>
 * 用途：对测试库直插库存行/子单/订单项/运单 fixture（确定性造数面——
 * 库存行初始化链路无端点接线、买家下单端点未就绪），walkthrough 订单面以本
 * 后门造数为准。发货端点（POST /seller/sub-orders/{id}/ship）走真实
 * 用例链路，不经本后门。
 * <p>
 * 运行形态（JDK 25 JEP 330 单文件源码模式，与 TestDbReset 同款：
 * 零编译产物，凭证走环境变量零落盘零打印）：
 * <pre>
 *   export ECOM_DB_PASSWORD=...
 *   CONN=$(find "$M2_REPO/com/mysql/mysql-connector-j" \
 *       -name 'mysql-connector-j-*.jar' 2>/dev/null | sort -V | tail -1)
 *   java --class-path "$CONN" scripts/SellerFixtureDb.java \
 *       seed-inventory &lt;shopId&gt; &lt;skuId&gt; &lt;available&gt; &lt;held&gt; &lt;sold&gt;
 * </pre>
 * 可选覆盖：ECOM_DB_HOST / ECOM_DB_PORT / ECOM_DB_NAME / ECOM_DB_USER
 * （默认值同 TestDbReset）。
 * <p>
 * 命令：
 * <ul>
 *     <li>{@code seed-inventory shopId skuId available held sold}——直插
 *         库存行（幂等：同 (tenant, sku_id) 冲突时整行覆盖）；</li>
 *     <li>{@code seed-order shopId subOrderId masterOrderId subOrderNo
 *         status waybillId(-1=null) productId skuA skuB}——直插子单 +
 *         双订单项（金额定型：商品 48000 / 运费 800 / 实付 48800，与
 *         装配面 fixture 同形状；地址/收货人快照固定）；</li>
 *     <li>{@code seed-waybill waybillId subOrderId company trackingNo}——
 *         直插运单 + 初始轨迹行（global 表；一子单一在途位 TRUE）。</li>
 * </ul>
 * 约束：本工具只做 INSERT / UPDATE 幂等修正，不 DELETE、不 DROP
 * （清理走 scripts/test-db-reset.sh 前置，同清理红线）。
 *
 * @author nona9961
 */
public final class SellerFixtureDb {

    private SellerFixtureDb() {
    }

    public static void main(String[] args) throws Exception {
        final String password = System.getenv("ECOM_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("环境变量 ECOM_DB_PASSWORD 未设置（运行模板从 MYSQL_ECOM_PW 映射导出）");
        }
        if (args.length < 1) {
            throw new IllegalArgumentException("缺少子命令：seed-inventory / seed-order / seed-waybill");
        }
        final String host = envOr("ECOM_DB_HOST", "127.0.0.1");
        final String port = envOr("ECOM_DB_PORT", "13306");
        final String db = envOr("ECOM_DB_NAME", "ecommerce_test");
        final String user = envOr("ECOM_DB_USER", "ecom_app");
        final String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?connectTimeout=5000&socketTimeout=8000&useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            switch (args[0]) {
                case "seed-inventory" -> seedInventory(conn, args);
                case "seed-order" -> seedOrder(conn, args);
                case "seed-waybill" -> seedWaybill(conn, args);
                default -> throw new IllegalArgumentException("未知子命令：" + args[0]);
            }
        }
    }

    /**
     * seed-inventory：直插库存行（幂等覆盖——同 (tenant_id, sku_id) 冲突
     * 时整行覆盖，重复执行安全）。
     */
    private static void seedInventory(Connection conn, String[] args) throws SQLException {
        final long shopId = Long.parseLong(require(args, 1, "shopId"));
        final long skuId = Long.parseLong(require(args, 2, "skuId"));
        final int available = Integer.parseInt(require(args, 3, "available"));
        final int held = Integer.parseInt(require(args, 4, "held"));
        final int sold = Integer.parseInt(require(args, 5, "sold"));
        final long id = 9_000_000_000L + skuId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO inventory_item (id, create_time, update_time, tenant_id,"
                        + " sku_id, available, held, sold, version)"
                        + " VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), ?, ?, ?, ?, ?, 0)"
                        + " ON DUPLICATE KEY UPDATE available = VALUES(available),"
                        + " held = VALUES(held), sold = VALUES(sold), update_time = CURRENT_TIMESTAMP(6)")) {
            ps.setLong(1, id);
            ps.setString(2, String.valueOf(shopId));
            ps.setLong(3, skuId);
            ps.setInt(4, available);
            ps.setInt(5, held);
            ps.setInt(6, sold);
            ps.executeUpdate();
        }
        System.out.println("seed-inventory ok: shopId=" + shopId + " skuId=" + skuId);
    }

    /**
     * seed-order：直插子单 + 双订单项（金额定型 48000/800/48800；
     * waybillId 传 -1 表示 null——未发货形态）。
     */
    private static void seedOrder(Connection conn, String[] args) throws SQLException {
        final long shopId = Long.parseLong(require(args, 1, "shopId"));
        final long subOrderId = Long.parseLong(require(args, 2, "subOrderId"));
        final long masterOrderId = Long.parseLong(require(args, 3, "masterOrderId"));
        final String subOrderNo = require(args, 4, "subOrderNo");
        final String status = require(args, 5, "status");
        final long waybillId = Long.parseLong(require(args, 6, "waybillId"));
        final long productId = Long.parseLong(require(args, 7, "productId"));
        final long skuA = Long.parseLong(require(args, 8, "skuA"));
        final long skuB = Long.parseLong(require(args, 9, "skuB"));

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sub_order (id, create_time, update_time, tenant_id,"
                        + " master_order_id, shop_id, sub_order_no, recipient, phone,"
                        + " province, city, district, detail, goods_amount, freight_amount,"
                        + " discount, paid_amount, status, waybill_id, timeout_at, timeout_type, claimed)"
                        + " VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), ?, ?, ?, ?,"
                        + " '张三', '13800000000', '浙江省', '杭州市', '西湖区', '文一西路 1 号',"
                        + " 48000, 800, 0, 48800, ?, ?, NULL, NULL, b'0')")) {
            ps.setLong(1, subOrderId);
            ps.setString(2, String.valueOf(shopId));
            ps.setLong(3, masterOrderId);
            ps.setLong(4, shopId);
            ps.setString(5, subOrderNo);
            ps.setString(6, status);
            if (waybillId < 0) {
                ps.setNull(7, java.sql.Types.BIGINT);
            } else {
                ps.setLong(7, waybillId);
            }
            ps.executeUpdate();
        }
        insertOrderItem(conn, shopId, subOrderId, productId, skuA, 15000L, 2,
                "/files/p3001.png");
        insertOrderItem(conn, shopId, subOrderId, productId, skuB, 6000L, 3, null);
        System.out.println("seed-order ok: shopId=" + shopId + " subOrderId=" + subOrderId);
    }

    /**
     * 直插订单项快照行（快照列定型：商品名「测试商品」、规格「颜色:黑,尺码:M」）。
     */
    private static void insertOrderItem(Connection conn, long shopId, long subOrderId,
                                        long productId, long skuId, long price, int qty,
                                        String image) throws SQLException {
        final long id = subOrderId * 10 + skuId % 10;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO order_item (id, create_time, update_time, tenant_id,"
                        + " sub_order_id, product_id, sku_id, product_name, unit_price,"
                        + " quantity, subtotal, main_image_url, spec_summary)"
                        + " VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), ?,"
                        + " ?, ?, ?, '测试商品', ?, ?, ?, ?, '颜色:黑,尺码:M')")) {
            ps.setLong(1, id);
            ps.setString(2, String.valueOf(shopId));
            ps.setLong(3, subOrderId);
            ps.setLong(4, productId);
            ps.setLong(5, skuId);
            ps.setLong(6, price);
            ps.setInt(7, qty);
            ps.setLong(8, price * qty);
            if (image == null) {
                ps.setNull(9, java.sql.Types.VARCHAR);
            } else {
                ps.setString(9, image);
            }
            ps.executeUpdate();
        }
    }

    /**
     * seed-waybill：直插运单（global 表）+ 初始轨迹行（在途位 TRUE——
     * 一子单一在途语义）。
     */
    private static void seedWaybill(Connection conn, String[] args) throws SQLException {
        final long waybillId = Long.parseLong(require(args, 1, "waybillId"));
        final long subOrderId = Long.parseLong(require(args, 2, "subOrderId"));
        final String company = require(args, 3, "company");
        final String trackingNo = require(args, 4, "trackingNo");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO waybill (id, create_time, update_time, sub_order_id,"
                        + " company, tracking_no, status, in_transit)"
                        + " VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), ?,"
                        + " ?, ?, 'IN_TRANSIT', b'1')")) {
            ps.setLong(1, waybillId);
            ps.setLong(2, subOrderId);
            ps.setString(3, company);
            ps.setString(4, trackingNo);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO waybill_track (id, create_time, update_time, waybill_id,"
                        + " status, occurred_at, description)"
                        + " VALUES (?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6),"
                        + " ?, 'IN_TRANSIT', CURRENT_TIMESTAMP(6), '运输中')")) {
            ps.setLong(1, waybillId * 10);
            ps.setLong(2, waybillId);
            ps.executeUpdate();
        }
        System.out.println("seed-waybill ok: waybillId=" + waybillId
                + " subOrderId=" + subOrderId);
    }

    private static String require(String[] args, int index, String name) {
        if (index >= args.length) {
            throw new IllegalArgumentException("缺少参数：" + name);
        }
        return args[index];
    }

    private static String envOr(String key, String fallback) {
        final String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}