package com.petplatform.id.core;

import cn.hutool.core.lang.Snowflake;
import com.petplatform.common.SnowflakeIdGenerator;
import java.util.Date;
import java.util.Objects;

/**
 * Single-instance Snowflake provider: Hutool owns the algorithm and the configured node id is
 * used directly. There is no database lease, renewal, verifier or fail-closed latch, so a
 * transient database/JVM stall cannot take the whole process's id generation down.
 *
 * <p>Every process must use a distinct node id. A deployment that needs automatic multi-instance
 * coordination and cross-restart high-water protection must use {@link HutoolSnowflakeIdProvider}
 * instead. This provider keeps the accepted id format (2026 epoch, 41/10/12 bits, 5+5 node split).
 */
public final class SimpleSnowflakeIdProvider implements SnowflakeIdGenerator {
    private static final System.Logger LOG =
            System.getLogger(SimpleSnowflakeIdProvider.class.getName());
    private final int nodeId;
    private final Snowflake sdk;

    public SimpleSnowflakeIdProvider(SnowflakeProviderSettings settings) {
        Objects.requireNonNull(settings);
        nodeId = settings.nodeId();
        sdk = new Snowflake(new Date(SnowflakeProviderSettings.EPOCH_MILLIS),
                settings.workerId(), settings.dataCenterId(), false, 0, 0);
        LOG.log(System.Logger.Level.INFO,
                "Snowflake single-instance provider nodeId={0}; every process must use a unique node id",
                nodeId);
    }

    @Override
    public long nextId() {
        return sdk.nextId();
    }

    public int nodeId() {
        return nodeId;
    }
}
