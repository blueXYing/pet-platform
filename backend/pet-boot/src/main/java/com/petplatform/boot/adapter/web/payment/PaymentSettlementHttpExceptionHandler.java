package com.petplatform.boot.adapter.web.payment;

import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
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
 * Scoped HTTP exception handler for Contract 80 Stage 7-BE admin settlement and merchant wallet
 * controllers. Emits the uniform {@code {success:false, code, message, data:null, traceId}} envelope.
 */
@RestControllerAdvice(assignableTypes = {
        AdminSettlementController.class,
        MerchantWalletController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class PaymentSettlementHttpExceptionHandler {
    private static final Map<String, Integer> STATUS = Map.of(
            CommonApiCodes.INVALID_ARGUMENT, 400,
            CommonApiCodes.UNAUTHORIZED, 401,
            CommonApiCodes.FORBIDDEN, 403,
            CommonApiCodes.NOT_FOUND, 404,
            CommonApiCodes.CONFLICT, 409,
            CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, 409,
            CommonApiCodes.RATE_LIMITED, 429,
            CommonApiCodes.DEPENDENCY_UNAVAILABLE, 503);

    @ExceptionHandler(ApiException.class)
    Map<String, Object> api(ApiException error, HttpServletResponse response) {
        return reply(STATUS.getOrDefault(error.code(), 500), error.code(), error.getMessage(), response);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestValueException.class,
            IllegalArgumentException.class
    })
    Map<String, Object> invalid(Exception ignored, HttpServletResponse response) {
        return reply(400, CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法", response);
    }

    @ExceptionHandler(org.springframework.core.NestedRuntimeException.class)
    Map<String, Object> unavailable(Exception ignored, HttpServletResponse response) {
        return reply(503, CommonApiCodes.DEPENDENCY_UNAVAILABLE, "依赖服务暂不可用，请保留原请求编号重试", response);
    }

    private static Map<String, Object> reply(int status, String code, String message, HttpServletResponse response) {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store, private");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("code", code);
        out.put("message", message);
        out.put("data", null);
        out.put("traceId", MDC.get(TraceContextFilter.TRACE_MDC_KEY));
        return out;
    }
}
