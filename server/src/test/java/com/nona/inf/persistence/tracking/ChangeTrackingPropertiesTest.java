package com.nona.inf.persistence.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.nona.annotation.ScaffoldGenerated;

/**
 * ChangeTrackingProperties 单元测试
 */
@ScaffoldGenerated
class ChangeTrackingPropertiesTest {

    @Test
    void shouldHaveDefaultValues() {
        ChangeTrackingProperties props = new ChangeTrackingProperties();

        assertEquals("id", props.getDefaultIdentifier());
        assertTrue(props.getValueTypePackages().isEmpty());
        assertTrue(props.getValueTypes().isEmpty());
        assertTrue(props.getIdentifierOverrides().isEmpty());
        assertTrue(props.getIdentifierMethods().isEmpty());
    }
}
