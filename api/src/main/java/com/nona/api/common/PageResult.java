package com.nona.api.common;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 统一分页响应体：所有分页查询接口的返回形态。
 *
 * @param records  当前页记录，空页返回空列表（非 null）
 * @param total    总记录数
 * @param pageNum  当前页码（回显请求值）
 * @param pageSize 当前每页条数（回显请求值）
 * @param <T>      记录类型
 */
public record PageResult<T>(List<T> records, long total, int pageNum, int pageSize) {

    /**
     * 紧凑构造器：防御 null，空页以空列表呈现。
     *
     * @param records  原始记录列表
     * @param total    总记录数
     * @param pageNum  页码
     * @param pageSize 条数
     */
    public PageResult {
        records = Objects.requireNonNullElse(records, Collections.emptyList());
    }

    /**
     * 按分页请求组装结果。
     *
     * @param records 当前页记录，允许 null（按空列表处理）
     * @param total   总记录数
     * @param query   分页请求（回显页码与条数）
     * @param <T>     记录类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(List<T> records, long total, PageQuery query) {
        return new PageResult<>(records, total, query.pageNum(), query.pageSize());
    }

    /**
     * 空结果（默认页码/条数，0 条记录）。
     *
     * @param <T> 记录类型
     * @return 空分页结果
     */
    public static <T> PageResult<T> empty() {
        return new PageResult<>(Collections.emptyList(), 0L, 1, PageQuery.DEFAULT_PAGE_SIZE);
    }

    /**
     * 总页数：向上取整，总数为 0 时为 0。
     *
     * @return 页数
     */
    public int totalPages() {
        if (total == 0) {
            return 0;
        }
        return (int) Math.ceil((double) total / pageSize);
    }

    /**
     * 是否还有下一页。
     *
     * @return 当前页之后仍存在记录时返回 true
     */
    public boolean hasNext() {
        return (long) pageNum * pageSize < total;
    }
}
