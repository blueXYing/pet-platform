package com.petplatform.thirdparty.biz.application.port;

/** Malware inspection. Missing/unavailable engines must throw and fail closed. */
@FunctionalInterface
public interface PrivateAssetScanner {
  ScanResult scan(byte[] content);

  record ScanResult(boolean clean, String providerVersion, String resultCode) {}
}
