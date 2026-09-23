package com.petplatform.service.biz.application;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.event.api.IntegrationEventPublisher;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Fail-closed ports for the service write slice. Every unresolved external fact (cover asset
 * ownership, presigned cover URL, admin authorization, outbox) refuses instead of degrading.
 */
public record ServiceWriteDependencies(
    ServiceCoverAssetPort coverAssets,
    ServiceCoverUrlPort coverUrls,
    ServiceReviewAuthorizationPort authorization,
    IntegrationEventPublisher events) {

  public static ServiceWriteDependencies unavailable() {
    return new ServiceWriteDependencies(
        (owner, ids) -> fail("cover asset provider is unavailable"),
        assetId -> fail("cover url signer is unavailable"),
        check -> fail("service review authorization provider is unavailable"),
        event -> fail("transactional outbox publisher is unavailable"));
  }

  private static <T> T fail(String message) {
    throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
  }

  /** Cover material ownership facts (31号 SERVICE_COVER pipeline, MER domain). */
  public interface ServiceCoverAssetPort {
    List<CoverAssetFact> resolveOwned(String ownerUserId, List<String> assetIds);

    record CoverAssetFact(
        String assetId, String ownerUserId, String status, String mediaType, long bytes) {}
  }

  /** Consumer-display presigned URL for a cover asset (CCR-OSS-001 signing family). */
  public interface ServiceCoverUrlPort {
    CoverUrl sign(String coverAssetId);

    record CoverUrl(String coverAssetId, String url, long expiresAtEpochSeconds) {}
  }

  /**
   * Transactional final authorization for admin review decisions, mirroring the merchant
   * application precedent (live session/action revalidation under the caller's lock).
   */
  public interface ServiceReviewAuthorizationPort {
    Decision check(Check check);

    record Check(
        String sessionId,
        long sessionGeneration,
        String operatorId,
        String actionCode,
        Resource resource,
        String purpose,
        Phase phase) {}

    record Resource(
        String resourceType, String resourceId, String merchantId, String cityCode,
        String scopeVersion) {}

    enum Phase {EXECUTE, READ_RESULT}

    record Decision(
        boolean allowed, OffsetDateTime checkedAt, String authzVersion, String reasonCode) {}
  }
}
