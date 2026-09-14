package com.petplatform.boot.adapter.web.admin;

import static com.petplatform.boot.adapter.web.admin.AdminAuthHttpModels.*;

import com.petplatform.admin.api.dto.AdminSessionView;
import com.petplatform.admin.biz.application.*;
import com.petplatform.boot.config.*;
import jakarta.servlet.http.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/auth")
@ConditionalOnProperty(prefix = "pet.auth.admin", name = "enabled", havingValue = "true")
public class AdminAuthController {
  private final AdminAuthService auth;
  private final AdminAuthProperties settings;

  public AdminAuthController(AdminAuthService auth, AdminAuthProperties settings) {
    this.auth = auth;
    this.settings = settings;
  }

  private static String rid(HttpServletRequest req) {
    return req.getHeader("X-Request-Id");
  }

  public static String trace(HttpServletRequest req) {
    Object previous = req.getAttribute("adminAuthTrace");
    if (previous instanceof String value) return value;
    String value = req.getHeader("X-Trace-Id");
    String trace =
        value != null && value.matches("[A-Za-z0-9._:-]{1,64}")
            ? value
            : UUID.randomUUID().toString();
    req.setAttribute("adminAuthTrace", trace);
    return trace;
  }

  private void origin(HttpServletRequest req) {
    if (!Objects.equals(settings.getOrigin(), req.getHeader("Origin")))
      throw new AdminAuthFailure(403, "COMMON_FORBIDDEN");
  }

  private String cookie(HttpServletRequest req) {
    origin(req);
    Cookie[] cookies = req.getCookies();
    if (cookies == null) throw AdminAuthFailure.unauthorized();
    var values =
        Arrays.stream(cookies).filter(c -> c.getName().equals("__Host-pet-admin-attempt")).toList();
    if (values.size() != 1) throw AdminAuthFailure.unauthorized();
    return values.getFirst().getValue();
  }

  private String attemptSecret(HttpServletRequest req) {
    String value = req.getHeader("X-Auth-Attempt");
    if (value == null || value.isBlank()) throw AdminAuthFailure.unauthorized();
    return value;
  }

  private static long id(String value) {
    try {
      if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw AdminAuthFailure.invalid();
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw AdminAuthFailure.invalid();
    }
  }

  private Envelope result(AdminSecretResult value, HttpServletRequest req) {
    return new Envelope("SUCCESS", "成功", value.data(), trace(req));
  }

  @ModelAttribute
  public void responseHeaders(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }

  @PostMapping("/attempts")
  public Envelope attempts(
      @RequestBody Request body, HttpServletRequest req, HttpServletResponse response) {
    try (body) {
      body.require(Set.of());
      origin(req);
      AdminSecretResult value = auth.createAttempt(rid(req), req.getRemoteAddr());
      String cookie =
          ResponseCookie.from("__Host-pet-admin-attempt", value.bindingCookie())
              .httpOnly(true)
              .secure(true)
              .sameSite("Strict")
              .path("/")
              .maxAge(600)
              .build()
              .toString();
      response.setStatus(201);
      response.setHeader(HttpHeaders.SET_COOKIE, cookie);
      return new Envelope("SUCCESS", "成功", value.data(), trace(req));
    }
  }

  @PostMapping("/login")
  public Envelope login(@RequestBody Request body, HttpServletRequest req) {
    try (body) {
      body.require(
          Set.of("attemptId", "account", "password", "captchaProof"),
          "attemptId",
          "account",
          "password");
      return result(
          auth.login(
              rid(req),
              body.id("attemptId"),
              attemptSecret(req),
              cookie(req),
              body.string("account"),
              body.secret("password"),
              body.string("captchaProof")),
          req);
    }
  }

  @GetMapping("/attempts/{attemptId}/requirements")
  public Envelope requirements(@PathVariable String attemptId, HttpServletRequest req) {
    return result(auth.requirements(id(attemptId), attemptSecret(req), cookie(req)), req);
  }

  @PostMapping("/captcha/challenges")
  public Envelope captcha(@RequestBody Request body, HttpServletRequest req) {
    try (body) {
      body.require(Set.of("attemptId"), "attemptId");
      return result(
          auth.createCaptcha(rid(req), body.id("attemptId"), attemptSecret(req), cookie(req)), req);
    }
  }

  @PostMapping("/captcha/verify")
  public Envelope verifyCaptcha(@RequestBody Request body, HttpServletRequest req) {
    try (body) {
      body.require(Set.of("attemptId", "captchaId", "answer"), "attemptId", "captchaId", "answer");
      return result(
          auth.verifyCaptcha(
              rid(req),
              body.id("attemptId"),
              attemptSecret(req),
              cookie(req),
              body.id("captchaId"),
              body.secret("answer")),
          req);
    }
  }

  @GetMapping("/attempts/{attemptId}/result")
  public Envelope attemptResult(
      @PathVariable String attemptId,
      @RequestParam(required = false) String requestId,
      HttpServletRequest req) {
    return result(
        auth.attemptResult(id(attemptId), attemptSecret(req), cookie(req), requestId), req);
  }

  private AdminSessionView view(HttpServletRequest req) {
    Object v = req.getAttribute(AdminBearerAuthenticationFilter.VIEW);
    return v instanceof AdminSessionView s
        ? s
        : auth.resolveSession(AdminBearerAuthenticationFilter.bearer(req));
  }

  @GetMapping("/session")
  public Envelope session(HttpServletRequest req) {
    AdminSessionView s = view(req);
    String expiry =
        new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(s.expiresAt());
    return result(
        new AdminSecretResult(
            Map.of(
                "sessionId",
                s.principal().sessionId(),
                "operatorId",
                s.principal().operatorId(),
                "audience",
                "ADMIN_WEB",
                "expiresAt",
                expiry,
                "idleExpiresAt",
                expiry,
                "authzVersion",
                s.permissions().authzVersion())),
        req);
  }

  @GetMapping("/permissions")
  public Envelope permissions(HttpServletRequest req) {
    var p = view(req).permissions();
    String checked =
        new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(p.checkedAt());
    return result(
        new AdminSecretResult(
            Map.of(
                "operatorId",
                p.operatorId(),
                "authzVersion",
                p.authzVersion(),
                "checkedAt",
                checked,
                "roles",
                p.roles(),
                "dataScope",
                p.dataScope(),
                "actionCodes",
                p.actionCodes())),
        req);
  }

  @PostMapping("/logout")
  public Envelope logout(@RequestBody Request body, HttpServletRequest req) {
    try (body) {
      body.require(Set.of());
      return result(auth.logout(rid(req), AdminBearerAuthenticationFilter.bearer(req)), req);
    }
  }

  @PostMapping("/activity")
  public Envelope activity(@RequestBody Request body, HttpServletRequest req) {
    try (body) {
      body.require(Set.of());
      return result(auth.activity(rid(req), AdminBearerAuthenticationFilter.bearer(req)), req);
    }
  }
}
