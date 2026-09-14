package com.petplatform.common.support;

import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.SnowflakeIdGenerator;

/** Test-only fixed sequence, NOT a Snowflake algorithm or a production uniqueness provider. */
public final class SequenceIdStub implements SnowflakeIdGenerator {
    private final long[] ids;
    private int cursor;

    public SequenceIdStub(long... ids) {
        if (ids == null || ids.length == 0) {
            throw new IllegalArgumentException("A finite test sequence is required");
        }
        this.ids = ids.clone();
        for (long id : this.ids) {
            new DecimalPublicIdCodec().toApi(id);
        }
    }

    @Override
    public long nextId() {
        if (cursor == ids.length) {
            throw new IllegalStateException("Test sequence exhausted; no production fallback");
        }
        return ids[cursor++];
    }
}
