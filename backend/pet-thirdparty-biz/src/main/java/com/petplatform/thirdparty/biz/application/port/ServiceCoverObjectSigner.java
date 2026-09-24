package com.petplatform.thirdparty.biz.application.port;

import java.time.Instant;

/** Storage pointers remain internal to thirdparty. Only exact immutable image versions are signed. */
public interface ServiceCoverObjectSigner {
  SignedObject sign(String objectKey, String versionRef);

  record SignedObject(String url, Instant expiresAt) {}
}
