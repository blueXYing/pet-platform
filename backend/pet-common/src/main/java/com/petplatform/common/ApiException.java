package com.petplatform.common;

import java.util.Map;

/**
 * Code-bearing failure for adapter dispatch: the global handler maps it to the
 * HTTP envelope with the status suggested by the Error Code Registry. Domain
 * layers may also express failures as typed results; this exception is the
 * bridge when they choose to throw a stable code.
 */
public class ApiException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;

    public ApiException(String code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(String code, String message, Map<String, Object> details) {
        super(message);
        if (code == null || code.isBlank() || code.length() > 64) {
            throw new IllegalArgumentException("code must contain 1..64 characters");
        }
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
