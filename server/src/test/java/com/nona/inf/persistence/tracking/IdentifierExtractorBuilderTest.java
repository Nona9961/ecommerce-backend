package com.nona.inf.persistence.tracking;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import com.nona.annotation.ScaffoldGenerated;

/**
 * IdentifierExtractorBuilder 单元测试
 */
@ScaffoldGenerated
class IdentifierExtractorBuilderTest {

    // ========== 测试用类 ==========

    static class TestEntity {
        private Long id;
        private String name;

        public TestEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
    }

    static class TestEntityWithUuid {
        private String uuid;

        public TestEntityWithUuid(String uuid) {
            this.uuid = uuid;
        }

        public String getUuid() { return uuid; }
    }

    // ========== 默认提取器测试 ==========

    @Test
    void shouldExtractByDefaultField() {
        ChangeTrackingProperties props = new ChangeTrackingProperties();
        props.setDefaultIdentifier("id");
        IdentifierExtractorBuilder builder = new IdentifierExtractorBuilder(props);

        Function<Object, Object> extractor = builder.getDefaultExtractor();
        TestEntity entity = new TestEntity(123L, "test");

        assertEquals(123L, extractor.apply(entity));
    }

    @Test
    void shouldFallbackToIdentityHashCodeWhenFieldNotFound() {
        ChangeTrackingProperties props = new ChangeTrackingProperties();
        props.setDefaultIdentifier("nonExistentField");
        IdentifierExtractorBuilder builder = new IdentifierExtractorBuilder(props);

        Function<Object, Object> extractor = builder.getDefaultExtractor();
        TestEntity entity = new TestEntity(123L, "test");

        // 回退到 identityHashCode
        assertEquals(System.identityHashCode(entity), extractor.apply(entity));
    }

    // ========== 字段覆盖配置测试 ==========

    @Test
    void shouldBuildExtractorFromFieldOverride() {
        ChangeTrackingProperties props = new ChangeTrackingProperties();
        props.getIdentifierOverrides().put(TestEntityWithUuid.class.getName(), "uuid");
        IdentifierExtractorBuilder builder = new IdentifierExtractorBuilder(props);

        Map<Class<?>, Function<Object, Object>> extractors = builder.build();
        TestEntityWithUuid entity = new TestEntityWithUuid("abc-123");

        assertTrue(extractors.containsKey(TestEntityWithUuid.class));
        assertEquals("abc-123", extractors.get(TestEntityWithUuid.class).apply(entity));
    }

    // ========== 方法配置测试 ==========

    @Test
    void shouldBuildExtractorFromMethodConfig() {
        ChangeTrackingProperties props = new ChangeTrackingProperties();
        props.getIdentifierMethods().put(TestEntity.class.getName(), "getName");
        IdentifierExtractorBuilder builder = new IdentifierExtractorBuilder(props);

        Map<Class<?>, Function<Object, Object>> extractors = builder.build();
        TestEntity entity = new TestEntity(123L, "testName");

        assertTrue(extractors.containsKey(TestEntity.class));
        assertEquals("testName", extractors.get(TestEntity.class).apply(entity));
    }

    @Test
    void shouldMethodConfigOverrideFieldConfig() {
        ChangeTrackingProperties props = new ChangeTrackingProperties();
        props.getIdentifierOverrides().put(TestEntity.class.getName(), "id");
        props.getIdentifierMethods().put(TestEntity.class.getName(), "getName");
        IdentifierExtractorBuilder builder = new IdentifierExtractorBuilder(props);

        Map<Class<?>, Function<Object, Object>> extractors = builder.build();
        TestEntity entity = new TestEntity(123L, "testName");

        // 方法配置优先
        assertEquals("testName", extractors.get(TestEntity.class).apply(entity));
    }

    // ========== 错误处理测试 ==========

    @Test
    void shouldThrowWhenClassNotFound() {
        ChangeTrackingProperties props = new ChangeTrackingProperties();
        props.getIdentifierOverrides().put("com.nonexistent.Class", "id");
        IdentifierExtractorBuilder builder = new IdentifierExtractorBuilder(props);

        assertThrows(IllegalStateException.class, builder::build);
    }

    // ========== getExtractorFor 测试 ==========

    @Test
    void shouldGetExtractorForRegisteredClass() {
        ChangeTrackingProperties props = new ChangeTrackingProperties();
        props.getIdentifierOverrides().put(TestEntity.class.getName(), "name");
        IdentifierExtractorBuilder builder = new IdentifierExtractorBuilder(props);
        Map<Class<?>, Function<Object, Object>> extractors = builder.build();

        Function<Object, Object> extractor = builder.getExtractorFor(extractors, TestEntity.class);
        TestEntity entity = new TestEntity(123L, "testName");

        assertEquals("testName", extractor.apply(entity));
    }

    @Test
    void shouldFallbackToDefaultFieldForUnregisteredClass() {
        ChangeTrackingProperties props = new ChangeTrackingProperties();
        props.setDefaultIdentifier("id");
        IdentifierExtractorBuilder builder = new IdentifierExtractorBuilder(props);
        Map<Class<?>, Function<Object, Object>> extractors = builder.build();

        Function<Object, Object> extractor = builder.getExtractorFor(extractors, TestEntity.class);
        TestEntity entity = new TestEntity(456L, "test");

        assertEquals(456L, extractor.apply(entity));
    }
}
