package com.nona.exceptions;

/**
 * ecommerce 业务域码及其默认 HTTP 状态映射。
 * <p>
 * 域码命名：小写点分 {@code domain.reason}，只增不改。段归属：
 * <ul>
 *     <li>{@code auth.*}：认证/授权域——未认证 {@code auth.unauthorized}、封禁与权限不足统一
 *         {@code auth.forbidden}（Security 链与登录用例同一语义）、凭证错误、注册用户名冲突</li>
 *     <li>{@code identity.*}：身份域——买家地址簿地址不存在 {@code identity.address_not_found}</li>
 *     <li>{@code catalog.*} / {@code inventory.*} / {@code order.*} / {@code payment.*}：
 *         各业务域段基码占位，随各域 WU 扩展（只增不改）</li>
 * </ul>
 * 通用码（{@code generic.*}）由 {@link BusinessCode} 承载。状态解析链：
 * 域码命中返回映射 → 未命中委托 {@link BusinessCode#defaultStatus(String)}（generic 码）→
 * 仍未知兜底 500（fail-closed，不抛异常）。
 *
 * @author nona9961
 */
public enum EcommerceBusinessCode {

    /**
     * 未认证（缺失或非法凭证）。
     */
    AUTH_UNAUTHORIZED("auth.unauthorized", 401),

    /**
     * 无权限或账号被禁止（封禁拦截与角色不匹配统一，Security 链与登录用例一致）。
     */
    AUTH_FORBIDDEN("auth.forbidden", 403),

    /**
     * 凭证错误（登录失败；统一消息防账号存在性泄露）。
     */
    AUTH_BAD_CREDENTIALS("auth.bad_credentials", 400),

    /**
     * 注册用户名冲突（同 type 联合唯一）。
     */
    AUTH_USERNAME_CONFLICT("auth.username_conflict", 400),

    /**
     * 身份域：买家地址簿目标地址不存在（编辑/删除/设置默认命中归属不明的地址）。
     */
    IDENTITY_ADDRESS_NOT_FOUND("identity.address_not_found", 404),

    /**
     * 身份域：入驻申请已存在（每个提交实体至多一个申请，提交/重提路径的账号冲突）。
     */
    IDENTITY_ONBOARDING_CONFLICT("identity.onboarding_conflict", 400),

    /**
     * 身份域：入驻申请不存在（按 ID 操作命中归属不明或不存在的申请）。
     */
    IDENTITY_ONBOARDING_NOT_FOUND("identity.onboarding_not_found", 404),

    /**
     * 身份域：入驻申请非法状态迁移（非 pending 审核 / 非 rejected 重提 / 终态修改 / 缺驳回原因）。
     */
    IDENTITY_ONBOARDING_STATE("identity.onboarding_state", 400),

    /**
     * 商品域占位基码：资源不存在。
     */
    CATALOG_NOT_FOUND("catalog.not_found", 404),

    /**
     * 商品域：店铺不存在（商家当前店铺缺失或已被删除）。
     */
    CATALOG_SHOP_NOT_FOUND("catalog.shop_not_found", 404),

    /**
     * 商品域：店铺分类不存在（目标分类不属于当前店铺，按不存在呈现）。
     */
    CATALOG_SHOP_CATEGORY_NOT_FOUND("catalog.shop_category_not_found", 404),

    /**
     * 商品域：店铺对象缺失（工厂参数防御，属请求构造错误）。
     */
    CATALOG_SHOP_REQUIRED("catalog.shop_required", 400),

    /**
     * 商品域：店铺名称不能为空。
     */
    CATALOG_SHOP_NAME_BLANK("catalog.shop_name_blank", 400),

    /**
     * 商品域：店铺分类名称不能为空（含创建时归属店铺缺失）。
     */
    CATALOG_SHOP_CATEGORY_NAME_BLANK("catalog.shop_category_name_blank", 400),

    /**
     * 商品域：店铺分类在聚合内重复（同一店铺分类 ID 唯一）。
     */
    CATALOG_SHOP_CATEGORY_DUPLICATE("catalog.shop_category_duplicate", 400),

    /**
     * 商品域：店铺分类已被商品引用（删除前须解除全部绑定——S7.1 商品
     * 店铺分类多对多绑定就位后启用，冲突语义 409）。
     */
    CATALOG_SHOP_CATEGORY_IN_USE("catalog.shop_category_in_use", 409),

    /**
     * 商品域：平台分类不存在（按 ID 操作命中不存在的平台分类）。
     */
    CATALOG_CATEGORY_NOT_FOUND("catalog.category_not_found", 404),

    /**
     * 商品域：平台分类名称不能为空。
     */
    CATALOG_CATEGORY_NAME_BLANK("catalog.category_name_blank", 400),

    /**
     * 商品域：平台分类名称冲突（名称全局唯一，禁用态分类名也不可复用）。
     */
    CATALOG_CATEGORY_NAME_CONFLICT("catalog.category_name_conflict", 400),

    /**
     * 商品域：平台分类排序值非法（排序必须为正数）。
     */
    CATALOG_CATEGORY_ORDER_INVALID("catalog.category_order_invalid", 400),

    /**
     * 商品域：品牌不存在（按 ID 操作命中不存在的品牌）。
     */
    CATALOG_BRAND_NOT_FOUND("catalog.brand_not_found", 404),

    /**
     * 商品域：品牌名称不能为空。
     */
    CATALOG_BRAND_NAME_BLANK("catalog.brand_name_blank", 400),

    /**
     * 商品域：品牌名称冲突（名称全局唯一，禁用态品牌名也不可复用）。
     */
    CATALOG_BRAND_NAME_CONFLICT("catalog.brand_name_conflict", 400),

    /**
     * 商品域：运费模板不存在（目标模板不属于当前店铺，按不存在呈现）。
     */
    CATALOG_FREIGHT_TEMPLATE_NOT_FOUND("catalog.freight_template_not_found", 404),

    /**
     * 商品域：运费模板名称不能为空。
     */
    CATALOG_FREIGHT_TEMPLATE_NAME_BLANK("catalog.freight_template_name_blank", 400),

    /**
     * 商品域：运费模板规则配置非法（规则类型缺失/未知、计费参数缺失或非正）。
     */
    CATALOG_FREIGHT_TEMPLATE_INVALID_RULES("catalog.freight_template_invalid_rules", 400),

    /**
     * 商品域：运费模板已停用（新订单不可用——计算器领域守卫）。
     */
    CATALOG_FREIGHT_TEMPLATE_DISABLED("catalog.freight_template_disabled", 400),

    /**
     * 商品域：运费计算输入非法（空模板、负商品金额、负件数——防御性拒绝）。
     */
    CATALOG_FREIGHT_INPUT_INVALID("catalog.freight_input_invalid", 400),

    /**
     * 商品域：草稿商品不存在（按 ID 操作命中不存在的商品或不属于当前店铺的商品）。
     */
    CATALOG_PRODUCT_NOT_FOUND("catalog.product_not_found", 404),

    /**
     * 商品域：商品对象缺失（工厂参数防御，属请求构造错误）。
     */
    CATALOG_PRODUCT_REQUIRED("catalog.product_required", 400),

    /**
     * 商品域：商品名称不能为空（草稿必填项，无名商品连草稿都不允许保存）。
     */
    CATALOG_PRODUCT_NAME_BLANK("catalog.product_name_blank", 400),

    /**
     * 商品域：商品图片 URL 不能为空（图片引用必填）。
     */
    CATALOG_PRODUCT_IMAGE_URL_BLANK("catalog.product_image_url_blank", 400),

    /**
     * 商品域：商品图片引用重复（同一商品内图片 ID 唯一）。
     */
    CATALOG_PRODUCT_IMAGE_DUPLICATE("catalog.product_image_duplicate", 400),

    /**
     * 商品域：商品图片不存在（目标图片不属于当前商品，按不存在呈现）。
     */
    CATALOG_PRODUCT_IMAGE_NOT_FOUND("catalog.product_image_not_found", 404),

    /**
     * 商品域：商品属性键不能为空（自定义属性键值对的键必填）。
     */
    CATALOG_PRODUCT_ATTRIBUTE_KEY_BLANK("catalog.product_attribute_key_blank", 400),

    /**
     * 商品域：商品属性键重复（同一商品内属性键唯一，重复键无业务意义）。
     */
    CATALOG_PRODUCT_ATTRIBUTE_KEY_DUPLICATE("catalog.product_attribute_key_duplicate", 400),

    /**
     * 商品域：商品属性重复（同一商品内属性 ID 唯一）。
     */
    CATALOG_PRODUCT_ATTRIBUTE_DUPLICATE("catalog.product_attribute_duplicate", 400),

    /**
     * 商品域：商品属性不存在（目标属性不属于当前商品，按不存在呈现）。
     */
    CATALOG_PRODUCT_ATTRIBUTE_NOT_FOUND("catalog.product_attribute_not_found", 404),

    /**
     * 商品域：规格模板非法（配置模板为空引用——模板整体替换路径的必填守卫）。
     */
    CATALOG_PRODUCT_SPEC_INVALID("catalog.product_spec_invalid", 400),

    /**
     * 商品域：规格维度名不能为空（维度必填非空，无名维度无业务意义）。
     */
    CATALOG_PRODUCT_SPEC_DIMENSION_NAME_BLANK("catalog.product_spec_dimension_name_blank", 400),

    /**
     * 商品域：规格维度名重复（同一模板内维度名唯一，同名维度无法区分组合归属）。
     */
    CATALOG_PRODUCT_SPEC_DIMENSION_DUPLICATE("catalog.product_spec_dimension_duplicate", 400),

    /**
     * 商品域：规格值不能为空（维度值列表必填非空且元素非空）。
     */
    CATALOG_PRODUCT_SPEC_VALUE_BLANK("catalog.product_spec_value_blank", 400),

    /**
     * 商品域：规格值重复（同一维度内值唯一，重复值使同一规格组合重复生成）。
     */
    CATALOG_PRODUCT_SPEC_VALUE_DUPLICATE("catalog.product_spec_value_duplicate", 400),

    /**
     * 商品域：SKU 组合数超上限（单商品 SKU 组合数超过防御性上限，拒绝配置）。
     */
    CATALOG_PRODUCT_SKU_COUNT_EXCEEDED("catalog.product_sku_count_exceeded", 400),

    /**
     * 商品域：SKU 价格非法（非空价格必须为正整数分，0 与负数无业务意义）。
     */
    CATALOG_PRODUCT_SKU_PRICE_INVALID("catalog.product_sku_price_invalid", 400),

    /**
     * 商品域：SKU 不存在（目标 SKU 不属于当前商品，按不存在呈现）。
     */
    CATALOG_PRODUCT_SKU_NOT_FOUND("catalog.product_sku_not_found", 404),

    /**
     * 商品域：编辑版本不存在（目标版本号不属于当前商品，按不存在呈现——
     * 回滚/历史查询命中不存在或跨店铺的版本）。
     */
    CATALOG_PRODUCT_VERSION_NOT_FOUND("catalog.product_version_not_found", 404),

    /**
     * 商品域：编辑版本号非法（回滚路径版本号非正数；防御性拒绝）。
     */
    CATALOG_PRODUCT_VERSION_INVALID("catalog.product_version_invalid", 400),

    /**
     * 商品域：商品状态非法（非法状态迁移——未提交直接审批、驳回态重复驳回、
     * 在售直接提交、下架态任何迁移等违例，由聚合状态机守卫抛出）。
     */
    CATALOG_PRODUCT_STATUS_ILLEGAL("catalog.product_status_illegal", 400),

    /**
     * 商品域：提交上架缺少规格模板（模板未配置或为空模板——提交要求：
     * 规格模板非空且至少一个启用 SKU）。
     */
    CATALOG_PRODUCT_SPEC_REQUIRED("catalog.product_spec_required", 400),

    /**
     * 商品域：提交上架缺少启用 SKU（规格模板已配置但 SKU 全部停用——
     * 至少一个启用 SKU 才可提交）。
     */
    CATALOG_PRODUCT_SKU_ENABLED_REQUIRED("catalog.product_sku_enabled_required", 400),

    /**
     * 商品域：提交上架存在未定价 SKU（提交要求：所有 SKU 价格必须已定价
     * 且为正整数分，未定价拒绝）。
     */
    CATALOG_PRODUCT_SKU_PRICE_UNSET("catalog.product_sku_price_unset", 400),

    /**
     * 商品域：提交上架缺少主图（提交要求：在售商品必须有主图；在售状态
     * 删除唯一主图同样拒绝）。
     */
    CATALOG_PRODUCT_MAIN_IMAGE_REQUIRED("catalog.product_main_image_required", 400),

    /**
     * 商品域：提交上架缺少平台类目（提交要求：在售商品必须挂载平台类目）。
     */
    CATALOG_PRODUCT_CATEGORY_REQUIRED("catalog.product_category_required", 400),

    /**
     * 商品域：提交上架缺少品牌（提交要求：在售商品必须挂载品牌）。
     */
    CATALOG_PRODUCT_BRAND_REQUIRED("catalog.product_brand_required", 400),

    /**
     * 商品域：审核驳回原因必填（驳回必须附原因，商家据此修改重提）。
     */
    CATALOG_PRODUCT_REJECT_REASON_BLANK("catalog.product_reject_reason_blank", 400),

    /**
     * 商品域：审核期内编辑被拒绝（待审核内容冻结：提交审核后不许再改，
     * 修改须等驳回后重新编辑重提）。
     */
    CATALOG_PRODUCT_EDIT_FORBIDDEN("catalog.product_edit_forbidden", 400),

    /**
     * 商品域：平台分类已禁用（新商品不可挂载禁用分类；已挂商品的保留历史归属合法）。
     */
    CATALOG_CATEGORY_DISABLED("catalog.category_disabled", 400),

    /**
     * 商品域：平台分类已被商品引用（禁用/删除前必须解除全部引用——冲突语义 409）。
     */
    CATALOG_CATEGORY_IN_USE("catalog.category_in_use", 409),

    /**
     * 商品域：品牌已禁用（新商品不可挂载禁用品牌；已挂商品的保留历史归属合法）。
     */
    CATALOG_BRAND_DISABLED("catalog.brand_disabled", 400),

    /**
     * 商品域：品牌已被商品引用（禁用/删除前必须解除全部引用——冲突语义 409）。
     */
    CATALOG_BRAND_IN_USE("catalog.brand_in_use", 409),

    /**
     * 库存域：SKU 库存不存在（按 ID/SKU 操作命中不存在或跨店铺的库存行，
     * 按不存在呈现——fail-closed 不泄露归属）。
     */
    INVENTORY_NOT_FOUND("inventory.not_found", 404),

    /**
     * 库存域：SKU 库存重复初始化（inventory_item.sku_id 唯一约束命中，
     * 同一 SKU 只允许一次初始化）。
     */
    INVENTORY_ALREADY_EXISTS("inventory.already_exists", 400),

    /**
     * 库存域：库存量不足（预占可售不足/扣减回滚预占不足——变更拒绝，
     * 防超卖前置守卫；并发防线为数据库条件更新）。
     */
    INVENTORY_INSUFFICIENT("inventory.insufficient", 400),

    /**
     * 库存域：变动数量非法（预占/扣减/回滚数量必须为正，非正无业务意义）。
     */
    INVENTORY_QUANTITY_INVALID("inventory.quantity_invalid", 400),

    /**
     * 库存域：流水行形态非法（零变更不产流水/前后快照与类型数量算术
     * 不一致/订单上下文与类型不符/手动调整缺操作人——审计行自相矛盾无
     * 意义，构造路径拒绝）。
     */
    INVENTORY_LOG_INVALID("inventory.log_invalid", 400),

    /**
     * 库存域：库存行装配形态非法（构造/读回路径：归属缺失、三态负值、
     * 乐观锁版本负值——非法形态的库存行无业务意义）。
     */
    INVENTORY_ITEM_INVALID("inventory.item_invalid", 400),

    /**
     * 库存域：重复变更请求（同订单同 SKU 同类型（幂等键 order_id,
     * sku_id, type）的变更已发生过——预查询拒绝或 DB 唯一约束兜底
     * 转换，重复请求不重复扣减/回滚，冲突语义 409）。
     */
    INVENTORY_LOG_DUPLICATE("inventory.log_duplicate", 409),

    /**
     * 订单域占位基码：资源不存在。
     */
    ORDER_NOT_FOUND("order.not_found", 404),

    /**
     * 订单域：购物车加购/改量的 SKU 不可购（SKU 不属于请求商品、规格已停用
     * 或商品生命周期状态不满足——请求与商品内容不匹配，属请求构造错误）。
     */
    ORDER_CART_SKU_NOT_FOUND("order.cart_sku_not_found", 400),

    /**
     * 订单域：购物车条目不存在（改量/勾选目标 SKU 不在当前买家购物车——
     * 列表过期或并发变更后操作已消失条目，显式提示而非静默忽略）。
     */
    ORDER_CART_ITEM_NOT_FOUND("order.cart_item_not_found", 404),

    /**
     * 订单域：购物车数量超过可售上限（加购/改量后数量大于买家可见可售量，
     * 拒绝本次写入并提示；购物车为软校验，下单预占为最终防线——冲突语义 409）。
     */
    ORDER_CART_QUANTITY_EXCEEDS("order.cart_quantity_exceeds", 409),

    /**
     * 支付域占位基码：资源不存在。
     */
    PAYMENT_NOT_FOUND("payment.not_found", 404),

    /**
     * 支付域：网关受理/查询参数非法（单号空白、金额非正）——渠道拒绝受理。
     */
    PAYMENT_GATEWAY_INVALID_ARGUMENT("payment.gateway_invalid_argument", 400),

    /**
     * 支付域：渠道回调非法（缺字段、金额非正、类型与字段不配套）——渠道自身防御。
     */
    PAYMENT_GATEWAY_CALLBACK_INVALID("payment.gateway_callback_invalid", 400),

    /**
     * 搜索域：价格区间非法（下界或上界为负值，或区间倒挂即上界小于下界；
     * 搜索检索条件校验位拒绝，B5.3 价格筛选入参把关）。
     */
    SEARCH_INVALID_PRICE_RANGE("search.invalid_price_range", 400),

    /**
     * 存储域：上传 contentType 不在白名单（nona.storage.allowed-content-types 可配）。
     */
    STORAGE_CONTENT_TYPE_NOT_ALLOWED("storage.content_type_not_allowed", 400),

    /**
     * 存储域：上传内容超出大小上限（nona.storage.max-size-bytes 可配；含系统 multipart 兜底拦截）。
     */
    STORAGE_FILE_TOO_LARGE("storage.file_too_large", 400),

    /**
     * 存储域：上传内容为空（0 字节无业务价值，与“不存在”语义区分）。
     */
    STORAGE_FILE_EMPTY("storage.file_empty", 400),

    /**
     * 存储域：objectKey 非法（白名单字符校验/路径穿越拒绝：{@code ../}、绝对路径、非法字符等）。
     */
    STORAGE_OBJECT_KEY_INVALID("storage.object_key_invalid", 400),

    /**
     * 存储域：目标文件不存在。
     */
    STORAGE_FILE_NOT_FOUND("storage.file_not_found", 404);

    private final String code;

    private final int defaultStatus;

    EcommerceBusinessCode(String code, int defaultStatus) {
        this.code = code;
        this.defaultStatus = defaultStatus;
    }

    /**
     * 业务码字符串（小写点分）。
     *
     * @return 业务码值
     */
    public String code() {
        return code;
    }

    /**
     * 查询业务码对应的默认 HTTP 状态码。
     * <p>
     * 解析链：域码命中返回映射 → 未命中委托 {@link BusinessCode#defaultStatus(String)}
     * （generic 码）→ 仍未知兜底 500（fail-closed，不抛异常）。
     *
     * @param code 业务码字符串，可为 null
     * @return 映射状态码
     */
    public static int defaultStatus(String code) {
        for (EcommerceBusinessCode ebc : values()) {
            if (ebc.code.equals(code)) {
                return ebc.defaultStatus;
            }
        }
        return BusinessCode.defaultStatus(code);
    }
}
