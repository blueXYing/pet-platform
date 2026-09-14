package com.petplatform.admin.biz.infrastructure.provider;

import java.time.Duration;
import java.util.Optional;

/** Dedicated volatile cache only. Implementations must never overwrite an existing reference. */
public interface AdminGrantCache extends AutoCloseable {
  void verifyVolatileConfiguration();

  void putIfAbsent(String reference, byte[] encrypted, Duration ttl);

  Optional<byte[]> get(String reference);

  @Override
  default void close() {}
}
