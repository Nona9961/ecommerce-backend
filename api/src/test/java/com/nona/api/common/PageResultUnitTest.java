package com.nona.api.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageResult} 单元测试：组装、空安全与页数计算。
 */
class PageResultUnitTest {

    @Test
    void shouldEchoQueryPagingFields() {
        final PageQuery query = new PageQuery(2, 20);
        final PageResult<String> result = PageResult.of(List.of("a"), 25L, query);
        assertThat(result.pageNum()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(25L);
    }

    @Test
    void shouldTreatNullRecordsAsEmptyList() {
        final PageResult<String> result = PageResult.of(null, 0L, new PageQuery(1, 10));
        assertThat(result.records()).isEmpty();
    }

    @Test
    void shouldComputeTotalPages() {
        assertThat(PageResult.of(List.of(), 0L, new PageQuery(1, 10)).totalPages()).isZero();
        assertThat(PageResult.of(List.of(), 25L, new PageQuery(1, 10)).totalPages()).isEqualTo(3);
        assertThat(PageResult.of(List.of(), 20L, new PageQuery(1, 10)).totalPages()).isEqualTo(2);
    }

    @Test
    void shouldComputeHasNextAtBoundary() {
        final PageResult<String> exactFit = PageResult.of(List.of(), 20L, new PageQuery(2, 10));
        assertThat(exactFit.hasNext()).isFalse();

        final PageResult<String> hasMore = PageResult.of(List.of(), 21L, new PageQuery(2, 10));
        assertThat(hasMore.hasNext()).isTrue();
    }

    @Test
    void shouldProvideEmptyResult() {
        final PageResult<String> result = PageResult.empty();
        assertThat(result.records()).isEmpty();
        assertThat(result.total()).isZero();
        assertThat(result.pageNum()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(PageQuery.DEFAULT_PAGE_SIZE);
        assertThat(result.totalPages()).isZero();
        assertThat(result.hasNext()).isFalse();
    }
}
