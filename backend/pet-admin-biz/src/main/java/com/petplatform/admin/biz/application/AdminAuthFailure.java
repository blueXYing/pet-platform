package com.petplatform.admin.biz.application;

/** Sanitized public failure; underlying SQL/credentials are deliberately not included. */
public class AdminAuthFailure extends RuntimeException {
  private final int status;
  private final String code;

  public AdminAuthFailure(int status, String code) {
    super(code, null, false, false);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static AdminAuthFailure unauthorized() {
    return new AdminAuthFailure(401, "COMMON_UNAUTHORIZED");
  }

  public static AdminAuthFailure invalid() {
    return new AdminAuthFailure(400, "COMMON_INVALID_ARGUMENT");
  }

  public static AdminAuthFailure unavailable() {
    return new AdminAuthFailure(503, "COMMON_DEPENDENCY_UNAVAILABLE");
  }

  public static AdminAuthFailure conflict() {
    return new AdminAuthFailure(409, "COMMON_CONFLICT");
  }
}
