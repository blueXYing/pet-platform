package com.petplatform.boot.adapter.web.merchant;

import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = MerchantStaffController.class)
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
public final class MerchantStaffExceptionHandler {
    @ExceptionHandler(ApiException.class)
    Map<String,Object> api(ApiException exception, HttpServletResponse response) {
        int status = switch (exception.code()) {
            case CommonApiCodes.INVALID_ARGUMENT -> 400;
            case CommonApiCodes.UNAUTHORIZED -> 401;
            case CommonApiCodes.FORBIDDEN -> 403;
            case CommonApiCodes.NOT_FOUND -> 404;
            case CommonApiCodes.CONFLICT, CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT -> 409;
            case CommonApiCodes.DEPENDENCY_UNAVAILABLE -> 503;
            default -> 500;
        };
        return error(status, exception.code(), exception.getMessage(), response);
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingRequestValueException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    Map<String,Object> invalid(Exception exception, HttpServletResponse response) {
        return error(400, CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法", response);
    }
    @ExceptionHandler(org.springframework.core.NestedRuntimeException.class)
    Map<String,Object> unavailable(Exception exception, HttpServletResponse response) {
        return error(503, CommonApiCodes.DEPENDENCY_UNAVAILABLE, "依赖暂不可用，请保留原请求编号重试", response);
    }
    private Map<String,Object> error(int status, String code, String message, HttpServletResponse response) {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("success", false);
        value.put("code", code);
        value.put("message", message);
        value.put("data", null);
        value.put("traceId", MDC.get(TraceContextFilter.TRACE_MDC_KEY));
        return value;
    }
}
