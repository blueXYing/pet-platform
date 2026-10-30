package com.petplatform.boot.adapter.web.c;

import com.petplatform.boot.adapter.web.admin.CommunityAdminController;
import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** CCR-COM-001 status map for the C community and admin moderation faces. */
@RestControllerAdvice(assignableTypes = {
        CommunityCController.class, CommunityMediaController.class, CommunityAdminController.class})
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CommunityHttpExceptionHandler {

    private static final Map<String, Integer> STATUS = Map.ofEntries(
            Map.entry(CommonApiCodes.INVALID_ARGUMENT, 400),
            Map.entry(CommonApiCodes.UNAUTHORIZED, 401),
            Map.entry(CommonApiCodes.FORBIDDEN, 403),
            Map.entry(CommonApiCodes.NOT_FOUND, 404),
            Map.entry(CommonApiCodes.CONFLICT, 409),
            Map.entry(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, 409),
            Map.entry(CommonApiCodes.RATE_LIMITED, 429),
            Map.entry(CommonApiCodes.INTERNAL_ERROR, 500),
            Map.entry(CommonApiCodes.DEPENDENCY_UNAVAILABLE, 503),
            Map.entry("COMMUNITY_POST_NOT_FOUND", 404),
            Map.entry("COMMUNITY_COMMENT_NOT_FOUND", 404),
            Map.entry("COMMUNITY_TOPIC_NOT_FOUND", 404),
            Map.entry("COMMUNITY_USER_NOT_FOUND", 404),
            Map.entry("COMMUNITY_POST_INVALID", 400),
            Map.entry("COMMUNITY_COMMENT_INVALID", 400),
            Map.entry("COMMUNITY_REPORT_INVALID", 400),
            Map.entry("COMMUNITY_MEDIA_INVALID", 400),
            Map.entry("COMMUNITY_MEDIA_TOO_LARGE", 413),
            Map.entry("COMMUNITY_STATE_NOT_ALLOWED", 409),
            Map.entry("COMMUNITY_ALREADY_REPORTED", 409));

    @ExceptionHandler(ApiException.class)
    ApiResponse<Void> apiException(ApiException error, HttpServletResponse response) {
        return reply(STATUS.getOrDefault(error.code(), 500), error.code(), error.getMessage(), response);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ApiResponse<Void> tooLarge(Exception error, HttpServletResponse response) {
        return reply(413, "COMMUNITY_MEDIA_TOO_LARGE", "图片不能超过 2MiB", response);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestValueException.class,
            IllegalArgumentException.class})
    ApiResponse<Void> invalid(Exception error, HttpServletResponse response) {
        return reply(400, CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法", response);
    }

    private static ApiResponse<Void> reply(int status, String code, String message,
            HttpServletResponse response) {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store, private");
        return ApiResponse.error(code, message, MDC.get(TraceContextFilter.TRACE_MDC_KEY));
    }
}
