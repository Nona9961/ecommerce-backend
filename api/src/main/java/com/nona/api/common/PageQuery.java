package com.nona.api.common;

/**
 * 统一分页请求参数：所有分页查询接口的入参形态。
 * <p>
 * 构造时归一化：页码最小为 1；每页条数小于 1 时取默认值，大于上限时取上限。
 * URL 参数与 JSON 字段统一 camelCase（pageNum/pageSize）。
 *
 * @param pageNum  页码，从 1 开始
 * @param pageSize 每页条数，默认 10，上限 100
 */
public record PageQuery(Integer pageNum, Integer pageSize) {

    /**
     * 默认每页条数
     */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * 每页条数上限（防御性限制，防止深分页拖垮查询）
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * 首条记录偏移量：供 SQL 侧直接使用。
     *
     * @return (pageNum - 1) * pageSize
     */
    public long offset() {
        return (long) (pageNum - 1) * pageSize;
    }

    /**
     * 紧凑构造器：归一化页码与条数到合法区间（含缺省 {@code null} 回落——
     * web 层以 {@code PageQuery} 为模型属性绑定时请求缺参不再 400，
     * 缺省 = 默认值语义）。
     *
     * @param pageNum  原始页码（缺省/小于 1 → 1）
     * @param pageSize 原始条数（缺省/小于 1 → 默认值，超出上限 → 上限）
     */
    public PageQuery {
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
    }
}
