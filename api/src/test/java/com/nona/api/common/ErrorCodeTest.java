package com.nona.api.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ErrorCode} 单元测试：分段约定、数值映射与反查。
 */
class ErrorCodeTest {

    @Test
    void shouldKeepSuccessCodeZero() {
        assertThat(ErrorCode.SUCCESS.code()).isZero();
        assertThat(ErrorCode.SUCCESS.defaultMessage()).isEqualTo("success");
    }

    @Test
    void shouldDefineSegmentsInIncreasingOrder() {
        assertThat(ErrorCode.SEGMENT_CATALOG).isEqualTo(2000);
        assertThat(ErrorCode.SEGMENT_INVENTORY).isEqualTo(3000);
        assertThat(ErrorCode.SEGMENT_ORDER).isEqualTo(4000);
        assertThat(ErrorCode.SEGMENT_PAYMENT).isEqualTo(5000);
    }

    @Test
    void shouldKeepSegmentSizeConsistent() {
        assertThat(ErrorCode.SEGMENT_INVENTORY - ErrorCode.SEGMENT_CATALOG)
                .isEqualTo(ErrorCode.SEGMENT_SIZE);
        assertThat(ErrorCode.SEGMENT_PAYMENT - ErrorCode.SEGMENT_ORDER)
                .isEqualTo(ErrorCode.SEGMENT_SIZE);
    }

    @Test
    void shouldKeepCommonCodesWithinCommonSegment() {
        for (final ErrorCode code : ErrorCode.values()) {
            if (code == ErrorCode.SUCCESS) {
                continue;
            }
            assertThat(code.code()).isGreaterThanOrEqualTo(1000).isLessThan(2000);
        }
    }

    @Test
    void shouldLookupKnownCode() {
        assertThat(ErrorCode.of(1001)).contains(ErrorCode.COMMON_INVALID_PARAM);
        assertThat(ErrorCode.of(1004)).contains(ErrorCode.COMMON_NOT_FOUND);
    }

    @Test
    void shouldReturnEmptyForUnknownCode() {
        assertThat(ErrorCode.of(1)).isEmpty();
        assertThat(ErrorCode.of(1999)).isEmpty();
        assertThat(ErrorCode.of(-1001)).isEmpty();
    }
}
