package com.petplatform.id.core;

import java.util.Objects;
import java.util.UUID;

/** A confirmed, immutable local receipt. requestStartedNanos never crosses JVM boundaries. */
public record SnowflakeNodeGrant(int nodeId, UUID incarnation, long fence,
        long startMillis, long throughMillis, long dbLeaseUntilMillis,
        long requestStartedNanos, long dbSampleMillis) {
    public SnowflakeNodeGrant {
        new SnowflakeProviderSettings(nodeId);
        Objects.requireNonNull(incarnation);
        if (fence <= 0 || startMillis < 0 || throughMillis < startMillis
                || throughMillis > SnowflakeProviderSettings.MAX_RELATIVE_MILLIS) {
            throw new IllegalArgumentException("Invalid confirmed grant");
        }
    }

    public void requireLocallyValid(long nowNanos) {
        long elapsed = nowNanos - requestStartedNanos;
        if (elapsed < 0 || elapsed >= (SnowflakeProviderSettings.LEASE_MILLIS
                - SnowflakeProviderSettings.LOCAL_MARGIN_MILLIS) * 1_000_000L) {
            throw new IllegalStateException("Local node permission expired");
        }
    }
}
