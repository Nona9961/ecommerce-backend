package com.nona.inf.persistence.reconstructor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.nona.annotation.ScaffoldGenerated;

/**
 * DeletionInfo 单元测试
 */
@DisplayName("DeletionInfo 测试")
@ScaffoldGenerated
class DeletionInfoTest {

    static class TestPO {
        private Long id;
    }

    @Test
    @DisplayName("应该正确存储 PO 类型和 ID")
    void shouldStorePoClassAndId() {
        // Given
        Class<?> poClass = TestPO.class;
        Long id = 123L;

        // When
        DeletionInfo info = new DeletionInfo(poClass, id);

        // Then
        assertThat(info.poClass()).isEqualTo(TestPO.class);
        assertThat(info.id()).isEqualTo(123L);
    }

    @Test
    @DisplayName("应该支持 Long 类型的 ID")
    void shouldSupportLongId() {
        // Long ID（与 BasePO.id 类型一致）
        DeletionInfo longIdInfo = new DeletionInfo(TestPO.class, 456L);
        assertThat(longIdInfo.id()).isEqualTo(456L);
    }

    @Test
    @DisplayName("相同 poClass 和 id 应该相等")
    void shouldBeEqualWhenSamePoClassAndId() {
        // Given
        DeletionInfo info1 = new DeletionInfo(TestPO.class, 100L);
        DeletionInfo info2 = new DeletionInfo(TestPO.class, 100L);

        // Then
        assertThat(info1).isEqualTo(info2);
        assertThat(info1.hashCode()).isEqualTo(info2.hashCode());
    }

    @Test
    @DisplayName("不同 poClass 或 id 应该不相等")
    void shouldNotBeEqualWhenDifferentPoClassOrId() {
        // Given
        DeletionInfo info1 = new DeletionInfo(TestPO.class, 100L);
        DeletionInfo info2 = new DeletionInfo(TestPO.class, 200L);
        DeletionInfo info3 = new DeletionInfo(AnotherPO.class, 100L);

        // Then
        assertThat(info1).isNotEqualTo(info2);
        assertThat(info1).isNotEqualTo(info3);
    }

    @Test
    @DisplayName("poClass 为 null 时应该抛出异常")
    void shouldThrowWhenPoClassIsNull() {
        assertThatThrownBy(() -> new DeletionInfo(null, 100L))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("poClass must not be null");
    }

    @Test
    @DisplayName("id 为 null 时应该抛出异常")
    void shouldThrowWhenIdIsNull() {
        assertThatThrownBy(() -> new DeletionInfo(TestPO.class, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("id must not be null");
    }

    static class AnotherPO {
        private Long id;
    }
}
