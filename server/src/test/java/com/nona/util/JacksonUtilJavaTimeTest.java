package com.nona.util;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import com.nona.annotation.ScaffoldGenerated;

/**
 * Tests for JavaTimeModule serialization/deserialization via {@link JacksonUtil}.
 *
 * @author nona
 */
@ScaffoldGenerated
class JacksonUtilJavaTimeTest {

    /** Fixed epoch millis representing 2025-06-13T00:00:00Z for deterministic test values. */
    private static final long EPOCH_MILLIS = 1718236800000L;

    @Test
    void shouldSerializeAndDeserializeInstant() {
        final Instant instant = Instant.ofEpochMilli(EPOCH_MILLIS);
        final String json = JacksonUtil.toJsonString(instant);

        assertThat(json).isNotNull();
        final Instant deserialized = JacksonUtil.fromJsonString(json, Instant.class);
        assertThat(deserialized).isEqualTo(instant);
    }

    @Test
    void shouldSerializeAndDeserializeLocalDateTime() {
        final LocalDateTime ldt = LocalDateTime.of(2025, 6, 13, 10, 30, 45);
        final String json = JacksonUtil.toJsonString(ldt);

        assertThat(json).isNotNull();
        final LocalDateTime deserialized = JacksonUtil.fromJsonString(json, LocalDateTime.class);
        assertThat(deserialized).isEqualTo(ldt);
    }

    @Test
    void shouldSerializeAndDeserializeLocalDate() {
        final LocalDate date = LocalDate.of(2025, 6, 13);
        final String json = JacksonUtil.toJsonString(date);

        assertThat(json).isNotNull();
        final LocalDate deserialized = JacksonUtil.fromJsonString(json, LocalDate.class);
        assertThat(deserialized).isEqualTo(date);
    }

    @Test
    void shouldSerializeAndDeserializeLocalTime() {
        final LocalTime time = LocalTime.of(10, 30, 45, 123_000_000);
        final String json = JacksonUtil.toJsonString(time);

        assertThat(json).isNotNull();
        final LocalTime deserialized = JacksonUtil.fromJsonString(json, LocalTime.class);
        assertThat(deserialized).isEqualTo(time);
    }

    @Test
    void shouldHandleJavaTimeInDtoRecords() {
        final record TimeDto(Instant instant, LocalDateTime localDateTime, LocalDate localDate) {
        }
        final TimeDto dto = new TimeDto(
                Instant.ofEpochMilli(EPOCH_MILLIS),
                LocalDateTime.of(2025, 6, 13, 10, 30, 45),
                LocalDate.of(2025, 6, 13)
        );

        final String json = JacksonUtil.toJsonString(dto);
        assertThat(json).isNotNull();
        assertThat(json).contains("2025-06-13");

        final TimeDto deserialized = JacksonUtil.fromJsonString(json, TimeDto.class);
        assertThat(deserialized).isEqualTo(dto);
    }

    @Test
    void shouldHandleJavaTimeInCollections() {
        final List<LocalDateTime> list = List.of(
                LocalDateTime.of(2025, 6, 13, 10, 30),
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );

        final String json = assertDoesNotThrow(() -> JacksonUtil.toJsonString(list));
        assertThat(json).isNotNull();

        final List<LocalDateTime> deserialized = JacksonUtil.fromJsonString(
                json, new TypeReference<List<LocalDateTime>>() {});
        assertThat(deserialized).containsExactlyElementsOf(list);
    }

    @Test
    void shouldHandleJavaTimeInMap() {
        final Map<String, Object> map = Map.of(
                "instant", Instant.ofEpochMilli(EPOCH_MILLIS),
                "date", LocalDate.of(2025, 6, 13)
        );

        final String json = assertDoesNotThrow(() -> JacksonUtil.toJsonString(map));
        assertThat(json).isNotNull();
        assertThat(json).contains("2025-06-13");
    }

    @Test
    void shouldNotFailOnNullInput() {
        assertThat(JacksonUtil.toJsonString(null)).isNull();
        assertThat(JacksonUtil.fromJsonString("", LocalDateTime.class)).isNull();
    }
}
