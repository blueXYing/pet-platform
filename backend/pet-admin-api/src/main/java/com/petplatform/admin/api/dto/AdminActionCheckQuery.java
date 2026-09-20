package com.petplatform.admin.api.dto;

import java.util.Objects;

/** Trusted internal final-authorization input. It deliberately contains no entry snapshot. */
public record AdminActionCheckQuery(
    String sessionId,
    long sessionGeneration,
    String operatorId,
    String actionCode,
    AdminResourceScope resource,
    String purpose,
    CheckPhase phase) {
  public AdminActionCheckQuery {
    requireId(sessionId, "session id");
    requireId(operatorId, "operator id");
    if (sessionGeneration < 0) throw new IllegalArgumentException("Invalid session generation");
    if (actionCode == null
        || actionCode.length() > 100
        || !actionCode.matches("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+"))
      throw new IllegalArgumentException("Invalid action code");
    Objects.requireNonNull(resource, "resource");
    if (purpose == null || !purpose.matches("[A-Z][A-Z0-9_]{0,63}"))
      throw new IllegalArgumentException("Invalid purpose");
    Objects.requireNonNull(phase, "phase");
  }

  private static void requireId(String value, String field) {
    try {
      if (value == null || !value.matches("[1-9][0-9]{0,18}") || Long.parseLong(value) < 1)
        throw new IllegalArgumentException("Invalid " + field);
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException("Invalid " + field);
    }
  }

  public enum CheckPhase {
    EXECUTE,
    READ_RESULT
  }
}
