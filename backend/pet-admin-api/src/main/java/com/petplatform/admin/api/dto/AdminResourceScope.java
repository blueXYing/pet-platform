package com.petplatform.admin.api.dto;

import java.util.Objects;

/**
 * Server-bound resource facts used by the admin authorization owner.
 *
 * <p>Callers must build this value from the locked resource projection. Request filters and
 * request-body city or merchant identifiers are not authorization evidence.
 */
public record AdminResourceScope(
    String resourceType,
    String resourceId,
    String merchantId,
    String cityCode,
    String scopeVersion) {
  public AdminResourceScope {
    if (resourceType == null || !resourceType.matches("[A-Z][A-Z0-9_]{0,63}"))
      throw new IllegalArgumentException("Invalid resource type");
    requireId(resourceId, "resource id");
    if (merchantId != null) requireId(merchantId, "merchant id");
    if (cityCode != null && (cityCode.isBlank() || cityCode.length() > 64))
      throw new IllegalArgumentException("Invalid city code");
    if (scopeVersion == null || scopeVersion.isBlank() || scopeVersion.length() > 128)
      throw new IllegalArgumentException("Invalid scope version");
  }

  private static void requireId(String value, String field) {
    Objects.requireNonNull(value, field);
    try {
      if (!value.matches("[1-9][0-9]{0,18}") || Long.parseLong(value) < 1)
        throw new IllegalArgumentException("Invalid " + field);
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException("Invalid " + field);
    }
  }
}
