package com.petplatform.thirdparty.api;

import com.petplatform.common.QueryContext;
import java.time.Instant;

/** Internal only. Caller must authorize the visible service's bound cover before signing. */
public interface ServiceCoverSigningApi {
  SignedServiceCover signServiceCover(String assetId, QueryContext context);

  /** Object storage pointers and credentials never leave the owning module. */
  record SignedServiceCover(String assetId, String signedUrl, Instant expiresAt) {}
}
