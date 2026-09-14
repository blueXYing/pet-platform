package com.petplatform.id.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.Date;
import java.util.HashSet;
import cn.hutool.core.lang.Snowflake;
import org.junit.jupiter.api.Test;

class SnowflakeSettingsTest {
    @Test void all1024MappingsUseTheSelectedSdkAndDecodeWithTheAcceptedEpoch() {
        assertTrue(Snowflake.class.getProtectionDomain().getCodeSource().getLocation().toString()
                .contains("hutool-core-5.8.47.jar"));
        var mappedNodes = new HashSet<Long>();
        var ids = new HashSet<Long>();
        for (int nodeId = 0; nodeId < 1024; nodeId++) {
            var settings = new SnowflakeProviderSettings(nodeId);
            var sdk = new Snowflake(new Date(SnowflakeProviderSettings.EPOCH_MILLIS),
                    settings.workerId(), settings.dataCenterId(), false, 0, 0);
            long before = System.currentTimeMillis();
            long id = sdk.nextId();
            long after = System.currentTimeMillis();
            long mapped = (sdk.getDataCenterId(id) << 5) | sdk.getWorkerId(id);
            assertEquals(nodeId, mapped);
            assertTrue(mappedNodes.add(mapped));
            assertTrue(id > 0 && ids.add(id));
            long decodedUtc = sdk.getGenerateDateTime(id);
            assertTrue(decodedUtc >= before && decodedUtc <= after, "wrong SDK epoch for node " + nodeId);
            assertEquals(SnowflakeProviderSettings.relativeMillis(decodedUtc), id >>> 22);
        }
        assertEquals(1024, mappedNodes.size());
        assertEquals(1024, ids.size());
        // SDK objects only: no provider threads, database nodes or production initialization.
    }

    @Test void explicitNodeMappingAndEpochDomain() {
        var node = new SnowflakeProviderSettings(71);
        assertEquals(7, node.workerId());
        assertEquals(2, node.dataCenterId());
        assertEquals(71L << 12, ((long) node.workerId() << 12) | ((long) node.dataCenterId() << 17));
        var last = new SnowflakeProviderSettings(1023);
        assertEquals(31, last.workerId());
        assertEquals(31, last.dataCenterId());
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeProviderSettings(-1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeProviderSettings(1024));
        long epoch = SnowflakeProviderSettings.EPOCH_MILLIS;
        long max = SnowflakeProviderSettings.MAX_RELATIVE_MILLIS;
        assertEquals(0, SnowflakeProviderSettings.relativeMillis(epoch));
        assertEquals(max, SnowflakeProviderSettings.relativeMillis(epoch + max));
        assertThrows(IllegalStateException.class, () -> SnowflakeProviderSettings.relativeMillis(epoch - 1));
        assertThrows(IllegalStateException.class, () -> SnowflakeProviderSettings.relativeMillis(epoch + max + 1));
        assertThrows(IllegalStateException.class, () -> SnowflakeProviderSettings.relativeMillis(epoch + (1L << 42)));
    }

    @Test void localPermissionUsesElapsedDifferenceAcrossSignedNanoBoundary() {
        long start = Long.MAX_VALUE - 500_000_000L;
        var grant = new SnowflakeNodeGrant(17, UUID.randomUUID(), 1, 1, 5000, 10000, start, 1);
        assertDoesNotThrow(() -> grant.requireLocallyValid(Long.MIN_VALUE));
        assertDoesNotThrow(() -> grant.requireLocallyValid(start + 8_999_999_999L));
        assertThrows(IllegalStateException.class, () -> grant.requireLocallyValid(start + 9_000_000_000L));
        assertThrows(IllegalStateException.class, () -> grant.requireLocallyValid(start - 1));
    }

    @Test void missingHostIntegrationRejectsEvenApparentlyVirginState() {
        var previous = new PreviousJvmExitVerifier.PreviousJvm(17, null, 0, "unverified", -1);
        assertThrows(IllegalStateException.class, () -> PreviousJvmExitVerifier.rejecting().verify(previous));
    }
}
