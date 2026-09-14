package com.petplatform.id.core;

import java.time.Instant;

/** Accepted initial settings. The only deployment-specific input is an explicitly assigned node. */
public record SnowflakeProviderSettings(int nodeId) {
    public static final long EPOCH_MILLIS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    public static final long MAX_RELATIVE_MILLIS = (1L << 41) - 1;
    public static final String FORMAT_IDENTITY = "epoch=1767225600000;t=41;n=10;s=12";
    public static final long BUDGET_MILLIS = 1_000;
    public static final long WINDOW_MILLIS = 5_000;
    public static final long REFILL_MILLIS = 1_000;
    public static final long LEASE_MILLIS = 10_000;
    public static final long RENEW_MILLIS = 2_000;
    public static final long LOCAL_MARGIN_MILLIS = 1_000;
    public static final long SKEW_MILLIS = 250;
    public static final long MAX_AHEAD_MILLIS = 10_000;

    public SnowflakeProviderSettings {
        if (nodeId < 0 || nodeId > 1023) throw new IllegalArgumentException("Explicit nodeId must be 0..1023");
    }

    public int workerId() { return nodeId & 31; }
    public int dataCenterId() { return nodeId >>> 5; }

    public static long relativeMillis(long absoluteMillis) {
        long relative = Math.subtractExact(absoluteMillis, EPOCH_MILLIS);
        if (relative < 0 || relative > MAX_RELATIVE_MILLIS) {
            throw new IllegalStateException("OS/DB time outside Snowflake epoch range");
        }
        return relative;
    }
}
