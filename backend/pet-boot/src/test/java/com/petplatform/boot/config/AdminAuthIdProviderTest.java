package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.id.core.SimpleSnowflakeIdProvider;
import org.junit.jupiter.api.Test;

class AdminAuthIdProviderTest {
    @Test
    void defaultNodeIdIsSingleInstanceValue() {
        assertEquals(1, new AdminAuthProperties().getNodeId());
    }

    @Test
    void adminAssemblyUsesSingleInstanceProvider() {
        var provider = new AdminAuthConfiguration().adminIdProvider(new AdminAuthProperties());
        assertInstanceOf(SimpleSnowflakeIdProvider.class, provider);
        assertTrue(provider.nextId() > 0);
    }
}
