package com.petplatform.merchant.biz.application;

import java.util.List;

/** Current, private-object metadata. Implementations must never return public URLs. */
@FunctionalInterface
public interface PrivateAssetQueryPort {
  List<PrivateAssetRef> resolveOwned(long ownerUserId, List<Long> assetIds);

  record PrivateAssetRef(
      long assetId, long ownerUserId, String sha256, String mediaType, int bytes, String status) {}
}
