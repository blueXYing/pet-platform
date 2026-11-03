package com.petplatform.boot.adapter.web.merchant;

import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.service.api.error.ServiceCategoryApiCodes;
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

/**
 * Scoped advice for the admin service category dictionary face (contract 74 §8). Without this
 * map the three domain codes would fall through the global handler as an unmapped 500.
 */
@RestControllerAdvice(assignableTypes = AdminServiceCategoryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ServiceCategoryHttpExceptionHandler {

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
            Map.entry(ServiceCategoryApiCodes.CATEGORY_NOT_FOUND, 404),
            Map.entry(ServiceCategoryApiCodes.CATEGORY_CODE_CONFLICT, 409),
            Map.entry(ServiceCategoryApiCodes.CATEGORY_VERSION_CONFLICT, 409));

    @ExceptionHandler(ApiException.class)
    ApiResponse<Void> apiException(ApiException error, HttpServletResponse response) {
        int status = STATUS.getOrDefault(error.code(), 500);
        return reply(status, error.code(), error.getMessage(), response);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestValueException.class,
            IllegalArgumentException.class})
    ApiResponse<Void> invalid(Exception error, HttpServletResponse response) {
        return reply(400, CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法", response);
    }

    private static ApiResponse<Void> reply(
            int status, String code, String message, HttpServletResponse response) {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store, private");
        return ApiResponse.error(code, message, MDC.get(TraceContextFilter.TRACE_MDC_KEY));
    }
}
