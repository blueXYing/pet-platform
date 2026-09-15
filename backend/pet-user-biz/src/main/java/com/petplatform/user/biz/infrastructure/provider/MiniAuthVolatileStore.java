package com.petplatform.user.biz.infrastructure.provider;

import java.time.Duration;
import java.util.Optional;

/**
 * Volatile key/value store for the mini-program auth slice: attempts, per-request command
 * tombstones, the 60-second grant recovery receipts and the sessions themselves. Per the
 * approved storage design the mini auth DDL is NOT authorized yet, so none of these facts are
 * allowed to become durable rows: the backing instance must run with persistence disabled and
 * losing it only forces clients to log in again. Raw codes, passwords and tokens are never
 * stored here — only server-side digests and already-issued grant receipts inside their
 * recovery window.
 */
public interface MiniAuthVolatileStore extends AutoCloseable {

    /** SET key value NX PX ttl; returns false when the key already existed. */
    boolean putIfAbsent(String key, String value, Duration ttl);

    /** SET key value PX ttl (overwrites). */
    void put(String key, String value, Duration ttl);

    Optional<String> get(String key);

    void delete(String key);

    /**
     * Fixed-window counter: INCR plus a non-extending TTL seeded on the first increment.
     * Returns the incremented count.
     */
    long incrementWindow(String key, Duration ttl);

    /** Rejects the store unless persistence (RDB snapshots and AOF) is verifiably off. */
    void verifyVolatileConfiguration();

    @Override
    void close();
}
