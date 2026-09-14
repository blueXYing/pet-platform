package com.petplatform.admin.api.dto;


/** A server-resolved view, never proof supplied by a caller. Contains no credential. */
public record AdminSessionPrincipal(
    String audience, String sessionId, String operatorId, long sessionGeneration) {
  public AdminSessionPrincipal {
    if (!"ADMIN_WEB".equals(audience) || !id(sessionId) || !id(operatorId) || sessionGeneration < 0)
      throw new IllegalArgumentException("Invalid admin principal");
  }

  private static boolean id(String value) {
    try {
      return value != null && value.matches("[1-9][0-9]{0,18}") && Long.parseLong(value) > 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
