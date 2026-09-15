package com.petplatform.boot.adapter.web.admin;

import static com.petplatform.boot.adapter.web.admin.AdminAuthHttpModels.Envelope;

import com.petplatform.admin.biz.application.AdminAuthFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = AdminAuthController.class)
@Order(0)
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
  public Envelope failure(
      AdminAuthFailure e,
      HttpServletRequest req,
      jakarta.servlet.http.HttpServletResponse response) {
    return reply(e.status(), e.code(), req, response);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
  public Envelope invalid(
      Exception e, HttpServletRequest req, jakarta.servlet.http.HttpServletResponse response) {
    return reply(400, "COMMON_INVALID_ARGUMENT", req, response);
  }

  @ExceptionHandler(Exception.class)
  public Envelope unknown(
      Exception e, HttpServletRequest req, jakarta.servlet.http.HttpServletResponse response) {
    return reply(500, "COMMON_INTERNAL_ERROR", req, response);
  }

  private Envelope reply(
      int status,
      String code,
      HttpServletRequest req,
      jakarta.servlet.http.HttpServletResponse response) {
    var service = auth.getIfAvailable();
    if (service != null && status < 500)
      service.recordRejected("AUTH_REQUEST_REJECTED", req.getHeader("X-Request-Id"));
    String message = status == 401 ? "账号或密码错误，或凭据已失效" : status == 503 ? "服务暂不可用，请稍后重试" : code;
    response.setStatus(status);
    response.setHeader("Cache-Control", "no-store");
    return new Envelope(code, message, null, AdminAuthController.trace(req));
  }
}
