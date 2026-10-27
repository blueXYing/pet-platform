package com.petplatform.thirdparty.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.thirdparty.api.PetAvatarSigningApi;
import com.petplatform.thirdparty.biz.application.port.ServiceCoverObjectSigner;
import com.petplatform.thirdparty.biz.infrastructure.persistence.PrivateAssetRepository;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * CCR-C-PET-AVATAR-001. Only a consumer-owned READY PET_AVATAR normalized object can be signed.
 * No arbitrary private-asset id or object key crosses this boundary.
 */
public final class PetAvatarSigningApiImpl implements PetAvatarSigningApi {
  private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
  private final PrivateAssetRepository repository;
  private final ServiceCoverObjectSigner signer;
  private final Clock clock;

  public PetAvatarSigningApiImpl(DataSource source, ServiceCoverObjectSigner signer, Clock clock) {
    this.repository = new PrivateAssetRepository(source);
    this.signer = Objects.requireNonNull(signer);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public SignedPetAvatar signOwnedAvatar(String ownerUserId, String assetId) {
    if (ownerUserId == null || assetId == null) throw unavailable();
    try {
      long owner = IDS.fromApi(ownerUserId);
      long asset = IDS.fromApi(assetId);
      return repository.transaction(mapper -> {
        var row = mapper.selectAssetForUpdate(asset);
        if (row == null
            || row.getOwnerUserId() != owner
            || !"PET_AVATAR".equals(row.getPurpose())
            || !"READY".equals(row.getStatus())
            || !("image/jpeg".equals(row.getMediaType()) || "image/png".equals(row.getMediaType()))
            || row.getBytes() == null || row.getBytes() < 1 || row.getBytes() > 10 * 1024 * 1024
            || row.getObjectSha256() == null || !row.getObjectSha256().matches("[a-f0-9]{64}")
            || row.getObjectKey() == null || !row.getObjectKey().endsWith("/normalized-v1")
            || row.getObjectVersionRef() == null) throw unavailable();
        var signed = signer.sign(row.getObjectKey(), row.getObjectVersionRef());
        if (signed == null || signed.expiresAt() == null
            || !signed.expiresAt().isAfter(clock.instant())) throw unavailable();
        URI url = URI.create(signed.url());
        if (!"https".equals(url.getScheme()) || url.getHost() == null
            || url.getUserInfo() != null || url.getFragment() != null
            || signed.url().length() > 2048) throw unavailable();
        return new SignedPetAvatar(assetId, signed.url(), signed.expiresAt());
      });
    } catch (RuntimeException failure) {
      // Never leak object keys, signer provider details or credentials.
      throw unavailable();
    }
  }

  private static ApiException unavailable() {
    return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "宠物头像暂不可用");
  }
}
