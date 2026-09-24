package com.petplatform.thirdparty.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.QueryContext;
import com.petplatform.thirdparty.api.ServiceCoverSigningApi;
import com.petplatform.thirdparty.biz.application.port.ServiceCoverObjectSigner;
import com.petplatform.thirdparty.biz.infrastructure.persistence.PrivateAssetRepository;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;

/** Approved SERVICE_COVER exception; application/identity materials can never be signed here. */
public final class ServiceCoverSigningApiImpl implements ServiceCoverSigningApi {
  private final PrivateAssetRepository repository;
  private final ServiceCoverObjectSigner signer;
  private final Clock clock;

  public ServiceCoverSigningApiImpl(DataSource source, ServiceCoverObjectSigner signer, Clock clock) {
    repository = new PrivateAssetRepository(source);
    this.signer = Objects.requireNonNull(signer);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public SignedServiceCover signServiceCover(String assetId, QueryContext context) {
    if (context == null) throw unavailable();
    try {
      long value = new DecimalPublicIdCodec().fromApi(assetId);
      // Serialize asset retirement/quarantine with the final check and local signing operation.
      return repository.transaction(mapper -> {
        var asset = mapper.selectAssetForUpdate(value);
        if (asset == null || !"SERVICE_COVER".equals(asset.getPurpose())
            || !"READY".equals(asset.getStatus()) || asset.getOwnerUserId() <= 0
            || !("image/jpeg".equals(asset.getMediaType()) || "image/png".equals(asset.getMediaType()))
            || asset.getBytes() == null || asset.getBytes() < 1 || asset.getBytes() > 10 * 1024 * 1024
            || asset.getObjectSha256() == null || !asset.getObjectSha256().matches("[a-f0-9]{64}")
            || asset.getObjectKey() == null || !asset.getObjectKey().endsWith("/normalized-v1")
            || asset.getObjectVersionRef() == null) throw unavailable();
        var signed = signer.sign(asset.getObjectKey(), asset.getObjectVersionRef());
        if (signed == null || signed.expiresAt() == null || !signed.expiresAt().isAfter(clock.instant())) throw unavailable();
        URI url = URI.create(signed.url());
        if (!"https".equals(url.getScheme()) || url.getHost() == null
            || url.getUserInfo() != null || url.getFragment() != null || signed.url().length() > 2048) throw unavailable();
        return new SignedServiceCover(assetId, signed.url(), signed.expiresAt());
      });
    } catch (RuntimeException failure) {
      // No pointer, signature, SDK request or provider credentials in errors/logs.
      throw unavailable();
    }
  }

  private static ApiException unavailable() {
    return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "服务封面暂不可用");
  }
}
