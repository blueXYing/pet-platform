package com.petplatform.merchant.biz.application;

import java.time.OffsetDateTime;

/**
 * Boot/admin-biz adapter performs the approved strong current authorization read for the
 * contract-64 platform governance read face (same shape as the contract-63 trade slices'
 * final-authorization ports; merchant-biz never depends on another biz). Collection reads
 * pass {@link MerchantGovernanceAuthorizationPort#checkCollection} before any row is scanned;
 * single resources and merchant-scoped collections pass {@link
 * MerchantGovernanceAuthorizationPort#check} with the locked row's scope facts. Fail-closed:
 * a null/unavailable decision never reads.
 */
public final class AdminGovernanceAuthorizationPort {

  private AdminGovernanceAuthorizationPort() {}

  @FunctionalInterface
  public interface MerchantGovernanceAuthorizationPort {
    Decision check(Check check);

    default Decision checkCollection(CollectionCheck check) {
      return null;
    }
  }

  public record Check(
      String sessionId,
      long sessionGeneration,
      String operatorId,
      String actionCode,
      Resource resource,
      String purpose,
      Phase phase) {}

  public record CollectionCheck(
      String sessionId,
      long sessionGeneration,
      String operatorId,
      String actionCode,
      String purpose,
      Phase phase) {}

  public record Resource(
      String resourceType,
      String resourceId,
      String merchantId,
      String cityCode,
      String scopeVersion) {}

  public enum Phase {
    EXECUTE,
    READ_RESULT
  }

  public record Decision(boolean allowed, OffsetDateTime checkedAt, String authzVersion, String reasonCode) {}
}
