package com.petplatform.merchant.biz.application;

import java.time.OffsetDateTime;

/** Boot/admin-biz adapter performs the approved strong current authorization read. */
@FunctionalInterface
public interface ApplicationFinalAuthorizationPort {
  Decision check(Check check);

  /** Independent current action/session gate for collection reads, before row scope filtering. */
  default Decision checkCollection(CollectionCheck check) {
    return null;
  }

  record Check(
      String sessionId,
      long sessionGeneration,
      String operatorId,
      String actionCode,
      Resource resource,
      String purpose,
      Phase phase) {}

  record CollectionCheck(
      String sessionId,
      long sessionGeneration,
      String operatorId,
      String actionCode,
      String purpose,
      Phase phase) {}

  record Resource(
      String resourceType,
      String resourceId,
      String merchantId,
      String cityCode,
      String scopeVersion) {}

  enum Phase {
    EXECUTE,
    READ_RESULT
  }

  record Decision(
      boolean allowed, OffsetDateTime checkedAt, String authzVersion, String reasonCode) {}
}
