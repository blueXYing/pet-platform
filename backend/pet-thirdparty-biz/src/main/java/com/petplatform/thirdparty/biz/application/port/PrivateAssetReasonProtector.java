package com.petplatform.thirdparty.biz.application.port;

/** Purpose-bound protection for persisted operator reasons. */
@FunctionalInterface
public interface PrivateAssetReasonProtector {
  byte[] protect(String purpose, String plaintext);
}
