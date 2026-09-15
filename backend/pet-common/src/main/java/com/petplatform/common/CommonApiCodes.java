package com.petplatform.common;

/**
 * Common error codes shared by every domain (docs/04-api/12 §2).
 * HTTP statuses live in the adapter; this class carries stable codes only.
 */
public final class CommonApiCodes {
    private CommonApiCodes() {}

    public static final String INVALID_ARGUMENT = "COMMON_INVALID_ARGUMENT";
    public static final String UNAUTHORIZED = "COMMON_UNAUTHORIZED";
    public static final String FORBIDDEN = "COMMON_FORBIDDEN";
    public static final String NOT_FOUND = "COMMON_NOT_FOUND";
    public static final String CONFLICT = "COMMON_CONFLICT";
    public static final String RATE_LIMITED = "COMMON_RATE_LIMITED";
    public static final String INTERNAL_ERROR = "COMMON_INTERNAL_ERROR";
    public static final String DEPENDENCY_UNAVAILABLE = "COMMON_DEPENDENCY_UNAVAILABLE";
    public static final String IDEMPOTENCY_KEY_CONFLICT = "IDEMPOTENCY_KEY_CONFLICT";
}
