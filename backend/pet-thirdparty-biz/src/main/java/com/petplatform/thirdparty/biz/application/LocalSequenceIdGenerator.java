package com.petplatform.thirdparty.biz.application;

import com.petplatform.common.SnowflakeIdGenerator;
import java.util.concurrent.atomic.AtomicLong;

/** CLI/test id source: monotonically increasing, unique per run (registry PK only). */
public final class LocalSequenceIdGenerator implements SnowflakeIdGenerator {
    private final AtomicLong counter = new AtomicLong(System.currentTimeMillis());

    @Override public long nextId() {
        return counter.getAndIncrement();
    }
}
