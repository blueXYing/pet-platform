package com.petplatform.boot.adapter.web.admin;

import static com.petplatform.boot.adapter.web.admin.AdminAuthHttpModels.Envelope;

import com.petplatform.admin.biz.application.AdminAuthFailure;
import com.petplatform.admin.biz.infrastructure.persistence.AdminAuthStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = AdminAuthController.class)
public class AdminAuthExceptionHandler {
  private final org.springframework.beans.factory.ObjectProvider<
          com.petplatform.admin.biz.application.AdminAuthService>
      auth;

  public AdminAuthExceptionHandler(
      org.springframework.beans.factory.ObjectProvider<
              com.petplatform.admin.biz.application.AdminAuthService>
          auth) {
    this.auth = auth;
  }

  @ExceptionHandler(AdminAuthFailure.class)
  public ResponseEntity<Envelope> failure(AdminAuthFailure e, HttpServletRequest req) {
    return reply(e.status(), e.code(), req);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
  public ResponseEntity<Envelope> invalid(Exception e, HttpServletRequest req) {
    return reply(400, "COMMON_INVALID_ARGUMENT", req);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Envelope> unknown(Exception e, HttpServletRequest req) {
    return reply(
        e instanceof AdminAuthStore.CommitUnknown ? 503 : 500,
        e instanceof AdminAuthStore.CommitUnknown
            ? "COMMON_DEPENDENCY_UNAVAILABLE"
            : "COMMON_INTERNAL_ERROR",
        req);
  }

  private ResponseEntity<Envelope> reply(int status, String code, HttpServletRequest req) {
    var service = auth.getIfAvailable();
    if (service != null && status < 500)
      service.recordRejected("AUTH_REQUEST_REJECTED", req.getHeader("X-Request-Id"));
    String message = status == 401 ? "账号或密码错误，或凭据已失效" : status == 503 ? "服务暂不可用，请稍后重试" : code;
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .body(new Envelope(code, message, null, AdminAuthController.trace(req)));
  }
}
