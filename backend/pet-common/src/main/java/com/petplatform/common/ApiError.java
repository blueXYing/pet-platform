package com.petplatform.common;

import java.util.Map;

/**
 * Stable internal error fact per Error Code Registry (docs/04-api/12 §1).
 * Chinese message is adjustable; business code must never branch on message.
 * HTTP mapping belongs to the adapter layer only.
 */
public record ApiError(String code, String message, String traceId, Map<String, Object> details) {
    public ApiError {
        if (code == null || code.isBlank() || code.length() > 64) {
            throw new IllegalArgumentException("code must contain 1..64 characters");
        }
        if (message != null && message.length() > 500) {
            throw new IllegalArgumentException("message must contain at most 500 characters");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null, null);
    }
}
