package com.petplatform.boot.config;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.application.PrivateAssetQueryPort;
import com.petplatform.thirdparty.api.PrivateAssetApi;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.ResolveOwnedPrivateAssetsQuery;
import java.util.List;
import java.util.Objects;

/** Composition-root bridge from MER's narrow port to the private-asset owner API. */
public final class MerchantPrivateAssetQueryAdapter implements PrivateAssetQueryPort {
  public static final String MERCHANT_APPLICATION_PURPOSE = "MERCHANT_APPLICATION_MATERIAL";
  /** Store photos (27号 §6.4 supplement): same pipeline, its own purpose vocabulary. */
  public static final String STORE_PHOTO_PURPOSE = "STORE_PHOTO";
  private final PrivateAssetApi assets;
  private final String purpose;

  public MerchantPrivateAssetQueryAdapter(PrivateAssetApi assets) {
    this(assets, MERCHANT_APPLICATION_PURPOSE);
  }

  public MerchantPrivateAssetQueryAdapter(PrivateAssetApi assets, String purpose) {
    this.assets = Objects.requireNonNull(assets);
    this.purpose = Objects.requireNonNull(purpose);
  }

  @Override
  public List<PrivateAssetRef> resolveOwned(long ownerUserId, List<Long> assetIds) {
    try {
      return assets
          .resolveOwned(
              new ResolveOwnedPrivateAssetsQuery(
                  Long.toString(ownerUserId),
                  assetIds.stream().map(String::valueOf).toList(),
                  purpose))
          .stream()
          .map(
              fact -> {
                if (fact.bytes() <= 0 || fact.bytes() > Integer.MAX_VALUE)
                  throw new IllegalArgumentException("invalid private asset size");
                return new PrivateAssetRef(
                    Long.parseLong(fact.assetId()),
                    Long.parseLong(fact.ownerUserId()),
                    fact.objectSha256(),
                    fact.mediaType(),
                    Math.toIntExact(fact.bytes()),
                    fact.status().name());
              })
          .toList();
    } catch (ApiException known) {
      throw known;
    } catch (RuntimeException failure) {
      throw new ApiException(
          CommonApiCodes.DEPENDENCY_UNAVAILABLE, "private asset facts are unavailable");
    }
  }
}
