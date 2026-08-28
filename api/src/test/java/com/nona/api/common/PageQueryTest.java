package com.nona.api.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageQuery} 单元测试：归一化边界与偏移计算。
 */
class PageQueryTest {

    @Test
    void shouldKeepValidValuesUnchanged() {
        final PageQuery query = new PageQuery(3, 25);
        assertThat(query.pageNum()).isEqualTo(3);
        assertThat(query.pageSize()).isEqualTo(25);
    }

    @Test
    void shouldNormalizePageNumBelowOneToFirstPage() {
        assertThat(new PageQuery(0, 10).pageNum()).isEqualTo(1);
        assertThat(new PageQuery(-5, 10).pageNum()).isEqualTo(1);
    }

    @Test
    void shouldNormalizeNonPositivePageSizeToDefault() {
        assertThat(new PageQuery(1, 0).pageSize()).isEqualTo(PageQuery.DEFAULT_PAGE_SIZE);
        assertThat(new PageQuery(1, -1).pageSize()).isEqualTo(PageQuery.DEFAULT_PAGE_SIZE);
    }

    @Test
    void shouldCapPageSizeAtUpperBound() {
        assertThat(new PageQuery(1, 101).pageSize()).isEqualTo(PageQuery.MAX_PAGE_SIZE);
        assertThat(new PageQuery(1, 10000).pageSize()).isEqualTo(PageQuery.MAX_PAGE_SIZE);
        assertThat(new PageQuery(1, PageQuery.MAX_PAGE_SIZE).pageSize())
                .isEqualTo(PageQuery.MAX_PAGE_SIZE);
    }

    @Test
    void shouldComputeOffset() {
        assertThat(new PageQuery(1, 10).offset()).isZero();
        assertThat(new PageQuery(3, 25).offset()).isEqualTo(50L);
    }

    @Test
    void shouldNormalizeBeforeComputingOffset() {
        assertThat(new PageQuery(0, 0).offset()).isZero();
        assertThat(new PageQuery(0, 0).pageNum()).isEqualTo(1);
        assertThat(new PageQuery(0, 0).pageSize()).isEqualTo(PageQuery.DEFAULT_PAGE_SIZE);
    }
}
