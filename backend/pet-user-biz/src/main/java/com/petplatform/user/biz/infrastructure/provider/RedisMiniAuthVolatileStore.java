package com.petplatform.user.biz.infrastructure.provider;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Dedicated Lettuce connection to the isolated volatile auth Redis (mirrors the admin grant
 * cache): own instance/namespace, persistence must be off, and every operation failure is a
 * dependency failure — never silently degraded to "no session".
 */
public final class RedisMiniAuthVolatileStore implements MiniAuthVolatileStore {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final String prefix;

    public RedisMiniAuthVolatileStore(
            String host, int port, String username, char[] password, String prefix) {
        if (host == null || host.isBlank() || port < 1 || port > 65535
                || prefix == null || !prefix.matches("auth001c[_A-Za-z0-9-]*:")) {
            throw new IllegalArgumentException("Explicit isolated C-end auth Redis required");
        }
        this.prefix = prefix;
        RedisURI.Builder uri = RedisURI.Builder.redis(host, port).withTimeout(Duration.ofSeconds(2));
        if (password != null && password.length > 0) {
            if (username != null && !username.isBlank()) {
                uri.withAuthentication(username, new String(password));
            } else {
                uri.withPassword(password);
            }
        }
        client = RedisClient.create(uri.build());
        try {
            connection = client.connect();
            verifyVolatileConfiguration();
        } catch (RuntimeException e) {
            client.shutdown();
            throw new IllegalStateException("C-end auth volatile store unavailable", e);
        }
    }

    @Override
    public void verifyVolatileConfiguration() {
        try {
            var commands = connection.sync();
            if (!"".equals(commands.configGet("save").get("save"))
                    || !"no".equals(commands.configGet("appendonly").get("appendonly"))) {
                throw new IllegalStateException("Auth Redis must disable RDB and AOF persistence");
            }
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Auth Redis configuration not verifiable", e);
        }
    }

    private String key(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9.:_-]{1,200}")) {
            throw new IllegalArgumentException("Invalid volatile store key");
        }
        return prefix + raw;
    }

    @Override
    public boolean putIfAbsent(String rawKey, String value, Duration ttl) {
        Objects.requireNonNull(ttl);
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("Positive TTL required");
        String stored = connection.sync().set(key(rawKey), value, SetArgs.Builder.nx().px(ttl.toMillis()));
        return "OK".equals(stored);
    }

    @Override
    public void put(String rawKey, String value, Duration ttl) {
        Objects.requireNonNull(ttl);
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("Positive TTL required");
        String stored = connection.sync().set(key(rawKey), value, SetArgs.Builder.px(ttl.toMillis()));
        if (!"OK".equals(stored)) throw new IllegalStateException("Volatile store write failed");
    }

    @Override
    public Optional<String> get(String rawKey) {
        return Optional.ofNullable(connection.sync().get(key(rawKey)));
    }

    @Override
    public void delete(String rawKey) {
        connection.sync().del(key(rawKey));
    }

    @Override
    public long incrementWindow(String rawKey, Duration ttl) {
        // INCR creates the key when absent; only the first increment seeds the TTL, so a busy
        // window never extends itself.
        String k = key(rawKey);
        Long count = connection.sync().incr(k);
        if (count == null) throw new IllegalStateException("Volatile counter unavailable");
        if (count == 1L) connection.sync().expire(k, ttl);
        return count;
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }

    @Override
    public String toString() {
        return "RedisMiniAuthVolatileStore[REDACTED]";
    }
}
