package com.petplatform.thirdparty.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.QueryContext;
import com.petplatform.thirdparty.api.StorePhotoSigningApi;
import com.petplatform.thirdparty.biz.application.port.ServiceCoverObjectSigner;
import com.petplatform.thirdparty.biz.infrastructure.persistence.PrivateAssetRepository;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract 74 §3.4 store-avatar signing (B-CARD-02): the ServiceCoverSigningApiImpl pattern
 * applied to the shared 31号 pipeline's STORE_PHOTO purpose. The card read suspends its read-only
 * snapshot so the asset's current facts are re-checked under a writable lock — only READY
 * STORE_PHOTO images with a versioned normalized object sign; application/identity materials,
 * covers, scanning or retired assets never reach the signer, and pointers/credentials never leave
 * this module. Failures surface as COMMON_DEPENDENCY_UNAVAILABLE without provider details.
 */
public final class StorePhotoSigningApiImpl implements StorePhotoSigningApi {
  private final PrivateAssetRepository repository;
  private final ServiceCoverObjectSigner signer;
  private final Clock clock;
  private final TransactionTemplate signingTransaction;

  public StorePhotoSigningApiImpl(DataSource source, ServiceCoverObjectSigner signer, Clock clock) {
    repository = new PrivateAssetRepository(source);
    // Null signer keeps the bean assembleable without an OSS connection; every sign attempt then
    // fails closed (503) instead of producing an unsigned URL, mirroring the cover port.
    this.signer = signer;
    this.clock = Objects.requireNonNull(clock, "clock is required");
    signingTransaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    signingTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    signingTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    signingTransaction.setTimeout(15);
  }

  @Override
  public SignedStorePhoto signStorePhoto(String assetId, QueryContext context) {
    if (context == null) throw unavailable();
    if (signer == null) throw unavailable();
    try {
      long value = new DecimalPublicIdCodec().fromApi(assetId);
      // Card visibility has already passed in its read-only snapshot. Suspend that transaction
      // so the final asset current-read lock is writable and cannot reuse stale READY facts.
      // Only local presigning occurs under this lock; no provider network request is made.
      return signingTransaction.execute(status -> repository.transaction(mapper -> {
        var asset = mapper.selectAssetForUpdate(value);
        if (asset == null || !"STORE_PHOTO".equals(asset.getPurpose())
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
        return new SignedStorePhoto(assetId, signed.url(), signed.expiresAt());
      }));
    } catch (RuntimeException failure) {
      // No pointer, signature, SDK request or provider credentials in errors/logs.
      throw unavailable();
    }
  }

  private static ApiException unavailable() {
    return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "门店图片暂不可用");
  }
}
