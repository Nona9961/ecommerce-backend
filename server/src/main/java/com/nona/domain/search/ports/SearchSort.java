package com.nona.domain.search.ports;

/**
 * 搜索排序（白名单：销量/价格/时间三轴）。
 * <p>
 * 方向语义内置定案：销量按降序（热卖在前）、价格按升序（低到高）、
 * 时间按降序（新上架在前）；排序字段与方向均不可由调用方拆分指定
 * （方向参数/更多字段属契约演进，只增不改）。枚举即白名单：
 * 排序 SQL 的列映射在基础设施层静态绑定，不存在调用方可控的列名
 * 输入，杜绝注入面。
 *
 * @author nona9961
 */
public enum SearchSort {

    /**
     * 销量降序（sales_total DESC；零销量商品排最后）。
     */
    SALES_DESC,

    /**
     * 价格升序（min_price ASC；起步价从低到高）。
     */
    PRICE_ASC,

    /**
     * 上新时间降序（updated_at DESC；新上架在前，搜索默认排序）。
     */
    TIME_DESC
}