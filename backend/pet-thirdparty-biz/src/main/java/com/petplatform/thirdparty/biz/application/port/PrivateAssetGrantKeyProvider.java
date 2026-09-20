package com.petplatform.thirdparty.biz.application.port;

import javax.crypto.SecretKey;

/** Separate externally supplied key used only for deterministic read-grant token derivation. */
@FunctionalInterface
public interface PrivateAssetGrantKeyProvider {
  KeyMaterial current();

  record KeyMaterial(String keyVersion, SecretKey hmacKey) {}
}
