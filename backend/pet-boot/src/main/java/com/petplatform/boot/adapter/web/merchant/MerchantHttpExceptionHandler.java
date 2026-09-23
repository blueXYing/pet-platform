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
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice(
    assignableTypes = {
      MerchantApplicationController.class,
      MerchantApplicationAdminController.class,
      CPrivateAssetController.class,
      AdminPrivateAssetController.class,
      MerchantAgreementController.class,
      MerchantApplicationCityController.class,
      MerchantServiceController.class,
      ServiceWriteAdminController.class
    })
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class MerchantHttpExceptionHandler {
  private static final Map<String, Integer> STATUS =
      Map.ofEntries(
          Map.entry(CommonApiCodes.INVALID_ARGUMENT, 400),
          Map.entry(CommonApiCodes.UNAUTHORIZED, 401),
          Map.entry(CommonApiCodes.FORBIDDEN, 403),
          Map.entry(CommonApiCodes.NOT_FOUND, 404),
          Map.entry(CommonApiCodes.CONFLICT, 409),
          Map.entry(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, 409),
          Map.entry(CommonApiCodes.DEPENDENCY_UNAVAILABLE, 503),
          // Service write slice (ADM-001, 12号 §12 additions).
          Map.entry(com.petplatform.service.api.error.ServiceWriteApiCodes
                  .SERVICE_STATE_NOT_ALLOWED, 409),
          Map.entry(com.petplatform.service.api.error.ServiceWriteApiCodes
                  .SERVICE_REVIEW_REASON_REQUIRED, 400),
          Map.entry(com.petplatform.thirdparty.api.PrivateAssetApiCodes.ASSET_NOT_READY, 409),
          Map.entry(com.petplatform.thirdparty.api.PrivateAssetApiCodes.ASSET_REJECTED, 422),
          Map.entry(com.petplatform.thirdparty.api.PrivateAssetApiCodes.GRANT_GONE, 410));

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

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  Map<String, Object> tooLarge(Exception ignored, HttpServletResponse response) {
    return error(413, CommonApiCodes.INVALID_ARGUMENT, "上传文件超过10MiB限制", response);
  }

  // DataAccessException and TransactionException both extend this non-persistence Spring base.
  // Keeping the web boundary on the base preserves fail-closed 503 mapping without coupling a
  // controller advice to repository/JDBC exception types (ARCH-004).
  @ExceptionHandler(org.springframework.core.NestedRuntimeException.class)
  Map<String, Object> unavailable(Exception ignored, HttpServletResponse response) {
    return error(503, CommonApiCodes.DEPENDENCY_UNAVAILABLE, "依赖暂不可用，请保留原请求编号重试", response);
  }

  @ExceptionHandler(CPrivateAssetController.UnsupportedPrivateAssetMediaType.class)
  Map<String, Object> unsupportedMedia(Exception ignored, HttpServletResponse response) {
    return error(415, CommonApiCodes.INVALID_ARGUMENT, "仅支持JPEG或PNG图片", response);
  }

  private Map<String, Object> error(
      int status, String code, String message, HttpServletResponse response) {
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
