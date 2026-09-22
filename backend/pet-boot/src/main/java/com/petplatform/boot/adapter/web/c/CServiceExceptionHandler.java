package com.petplatform.boot.adapter.web.c;

import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Scoped advice for the C-end controllers (higher precedence than the global handler): adds
 * the domain codes of the user slice to the common registry statuses and keeps every error
 * body the HTTP10 envelope with data=null. Class names only in logs — never payloads.
 */
@RestControllerAdvice(assignableTypes = {
        CAuthController.class, CAccountController.class, CPetController.class,
        CProfileController.class, CMerchantMembershipController.class,
        CNotificationController.class, CServiceController.class})
@Order(0)
public class CServiceExceptionHandler {

    private static final System.Logger LOG =
            System.getLogger(CServiceExceptionHandler.class.getName());

    private static final Map<String, Integer> STATUS = Map.ofEntries(
            Map.entry("COMMON_INVALID_ARGUMENT", 400),
            Map.entry("COMMON_UNAUTHORIZED", 401),
            Map.entry("COMMON_FORBIDDEN", 403),
            Map.entry("USER_FROZEN", 403),
            Map.entry("COMMON_NOT_FOUND", 404),
            Map.entry("PET_NOT_FOUND", 404),
            Map.entry("SERVICE_NOT_FOUND", 404),
            Map.entry("COMMON_CONFLICT", 409),
            Map.entry("IDEMPOTENCY_KEY_CONFLICT", 409),
            Map.entry("COMMON_RATE_LIMITED", 429),
            Map.entry("COMMON_INTERNAL_ERROR", 500),
            Map.entry("COMMON_DEPENDENCY_UNAVAILABLE", 503));

    @ExceptionHandler(ApiException.class)
    public ApiResponse<Void> apiException(ApiException error, HttpServletResponse response) {
        Integer status = STATUS.get(error.code());
        if (status == null) {
            LOG.log(System.Logger.Level.WARNING, "Unmapped api code: {0}", error.code());
            status = 500;
        }
        return reply(status, error.code(), error.getMessage(), response);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ApiResponse<Void> invalid(Exception error, HttpServletResponse response) {
        return reply(400, "COMMON_INVALID_ARGUMENT", "请求参数不合法", response);
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> unknown(Exception error, HttpServletResponse response) {
        LOG.log(System.Logger.Level.ERROR, "Unhandled C-end exception: {0}", error.getClass().getName());
        return reply(500, "COMMON_INTERNAL_ERROR", "服务内部错误，请稍后重试", response);
    }

    private static ApiResponse<Void> reply(
            int status, String code, String message, HttpServletResponse response) {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.error(code, message, MDC.get(TraceContextFilter.TRACE_MDC_KEY));
    }
}
