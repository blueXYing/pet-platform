package com.petplatform.admin.api.dto;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Current authorization decision. A denial is data; unavailable or inconsistent reads fail. */
public record AdminActionDecision(
    boolean allowed, OffsetDateTime checkedAt, String authzVersion, String reasonCode) {
  public AdminActionDecision {
    Objects.requireNonNull(checkedAt, "checkedAt");
    if (authzVersion == null || authzVersion.isBlank() || authzVersion.length() > 128)
      throw new IllegalArgumentException("Invalid authz version");
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,63}"))
      throw new IllegalArgumentException("Invalid reason code");
    if (allowed != "ALLOWED".equals(reasonCode))
      throw new IllegalArgumentException("Decision and reason disagree");
  }
}
