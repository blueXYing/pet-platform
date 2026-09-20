package com.petplatform.boot.adapter.web.merchant;

import com.petplatform.boot.config.TraceContextFilter;
import com.petplatform.common.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(
    assignableTypes = {
      MerchantApplicationController.class,
      MerchantApplicationAdminController.class,
      MerchantAgreementController.class,
      MerchantApplicationCityController.class
    })
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class MerchantHttpExceptionHandler {
  private static final Map<String, Integer> STATUS =
      Map.of(
          CommonApiCodes.INVALID_ARGUMENT,
          400,
          CommonApiCodes.UNAUTHORIZED,
          401,
          CommonApiCodes.FORBIDDEN,
          403,
          CommonApiCodes.NOT_FOUND,
          404,
          CommonApiCodes.CONFLICT,
          409,
          CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT,
          409,
          CommonApiCodes.DEPENDENCY_UNAVAILABLE,
          503);

  @ExceptionHandler(ApiException.class)
  Map<String, Object> api(ApiException error, HttpServletResponse response) {
    return error(
        STATUS.getOrDefault(error.code(), 500), error.code(), error.getMessage(), response);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestValueException.class,
    IllegalArgumentException.class
  })
  Map<String, Object> invalid(Exception ignored, HttpServletResponse response) {
    return error(400, CommonApiCodes.INVALID_ARGUMENT, "请求参数不合法", response);
  }

  private Map<String, Object> error(
      int status, String code, String message, HttpServletResponse response) {
    response.setStatus(status);
    response.setHeader("Cache-Control", "no-store");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("success", false);
    out.put("code", code);
    out.put("message", message);
    out.put("data", null);
    out.put("traceId", MDC.get(TraceContextFilter.TRACE_MDC_KEY));
    return out;
  }
}
