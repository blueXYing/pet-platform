package com.petplatform.admin.biz.infrastructure.provider;

import com.petplatform.admin.biz.application.AdminAuthFailure;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** A separate Redis connection/instance; never uses the application's ordinary cache database. */
public final class RedisAdminGrantCache implements AdminGrantCache {
  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;
  private final String prefix;

  public RedisAdminGrantCache(
      String host, int port, String username, char[] password, String prefix) {
    if (host == null
        || host.isBlank()
        || port < 1
        || port > 65535
        || prefix == null
        || !prefix.matches("auth001[_A-Za-z0-9-]*:"))
      throw new IllegalArgumentException("Explicit isolated auth Redis required");
    this.prefix = prefix;
    RedisURI.Builder uri = RedisURI.Builder.redis(host, port).withTimeout(Duration.ofSeconds(2));
    if (password != null && password.length > 0) {
      if (username != null && !username.isBlank())
        uri.withAuthentication(username, new String(password));
      else uri.withPassword(password);
    }
    client = RedisClient.create(uri.build());
    try {
      connection = client.connect();
      verifyVolatileConfiguration();
    } catch (RuntimeException e) {
      client.shutdown();
      throw AdminAuthFailure.unavailable();
    }
  }

  @Override
  public void verifyVolatileConfiguration() {
    try {
      var c = connection.sync();
      if (!"".equals(c.configGet("save").get("save"))
          || !"no".equals(c.configGet("appendonly").get("appendonly")))
        throw AdminAuthFailure.unavailable();
    } catch (RuntimeException e) {
      throw AdminAuthFailure.unavailable();
    }
  }

  private String key(String ref) {
    if (ref == null || !ref.matches("[A-Za-z0-9_-]{1,128}")) throw AdminAuthFailure.invalid();
    return prefix + ref;
  }

  @Override
  public void putIfAbsent(String reference, byte[] encrypted, Duration ttl) {
    if (ttl.isNegative() || ttl.isZero()) throw AdminAuthFailure.unauthorized();
    try {
      verifyVolatileConfiguration();
      String value = Base64.getEncoder().encodeToString(encrypted);
      String k = key(reference);
      String ok = connection.sync().set(k, value, SetArgs.Builder.nx().px(ttl.toMillis()));
      if (ok == null) {
        String old = connection.sync().get(k);
        if (old == null
            || !MessageDigest.isEqual(
                old.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
          throw AdminAuthFailure.unavailable();
      }
    } catch (RuntimeException e) {
      if (e instanceof AdminAuthFailure a) throw a;
      throw AdminAuthFailure.unavailable();
    }
  }

  @Override
  public Optional<byte[]> get(String reference) {
    try {
      verifyVolatileConfiguration();
      String value = connection.sync().get(key(reference));
      return value == null ? Optional.empty() : Optional.of(Base64.getDecoder().decode(value));
    } catch (RuntimeException e) {
      throw AdminAuthFailure.unavailable();
    }
  }

  @Override
  public void close() {
    connection.close();
    client.shutdown();
  }

  @Override
  public String toString() {
    return "RedisAdminGrantCache[REDACTED]";
  }
}
