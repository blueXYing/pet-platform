package com.petplatform.common;

/**
 * HTTP response envelope per HTTP API Contract (docs/04-api/10):
 * {code, message, data, traceId}; success code is SUCCESS, error bodies carry data=null.
 * traceId is supplied explicitly by the adapter from the request chain.
 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {
    public static final String SUCCESS_CODE = "SUCCESS";

    public ApiResponse {
        if (code == null || code.isBlank() || code.length() > 64) {
            throw new IllegalArgumentException("code must contain 1..64 characters");
        }
    }

    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(SUCCESS_CODE, "ok", data, traceId);
    }

    public static ApiResponse<Void> error(String code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, traceId);
    }

    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}
