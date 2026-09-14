package com.petplatform.common;

/**
 * Generates positive Snowflake IDs for internal persistence, not requestId/task_key.
 * No production provider or bean is supplied by S1. A provider must fail safely
 * when worker ownership, clock rollback or the restart high-water mark is unsafe.
 * Public/API boundaries must encode returned values with PublicIdCodec.
 */
@FunctionalInterface
public interface SnowflakeIdGenerator {
    long nextId();
}
