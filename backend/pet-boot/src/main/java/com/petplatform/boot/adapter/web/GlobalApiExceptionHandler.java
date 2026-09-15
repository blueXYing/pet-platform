package com.petplatform.boot.adapter.web;

import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.boot.config.TraceContextFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

/**
 * Last-resort exception mapping for every controller outside the scoped admin
 * auth advice (which keeps a higher precedence). Emits the HTTP10 envelope
 * {code, message, data=null, traceId} with statuses suggested by Error12 §2,
 * never leaks internals, and logs exception class names only (baseline 21 §3).
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalApiExceptionHandler {
    private static final System.Logger LOG = System.getLogger(GlobalApiExceptionHandler.class.getName());
    private static final Map<String, Integer> STATUS = Map.of(
            CommonApiCodes.INVALID_ARGUMENT, 400,
            CommonApiCodes.UNAUTHORIZED, 401,
            CommonApiCodes.FORBIDDEN, 403,
            CommonApiCodes.NOT_FOUND, 404,
            CommonApiCodes.CONFLICT, 409,
            CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, 409,
            CommonApiCodes.RATE_LIMITED, 429,
            CommonApiCodes.INTERNAL_ERROR, 500,
            CommonApiCodes.DEPENDENCY_UNAVAILABLE, 503);

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
            MissingRequestValueException.class,
            MissingServletRequestPartException.class,
            IllegalArgumentException.class})
    public ApiResponse<Void> invalidArgument(Exception error, HttpServletResponse response) {
        // Unknown fields, duplicate JSON keys and wrong types all land here (HTTP10 line 1630).
        return reply(400, CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法", response);
    }

    @ExceptionHandler({
            NoHandlerFoundException.class,
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class})
    public ApiResponse<Void> notFound(Exception error, HttpServletResponse response) {
        return reply(404, CommonApiCodes.NOT_FOUND, "资源不存在", response);
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> unknown(Exception error, HttpServletResponse response) {
        // Class name only: raw messages may carry SQL parameters or business payloads.
        LOG.log(System.Logger.Level.ERROR, "Unhandled exception: {0}", error.getClass().getName());
        return reply(500, CommonApiCodes.INTERNAL_ERROR, "服务内部错误，请稍后重试", response);
    }

    private ApiResponse<Void> reply(int status, String code, String message, HttpServletResponse response) {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.error(code, message, MDC.get(TraceContextFilter.TRACE_MDC_KEY));
    }
}
