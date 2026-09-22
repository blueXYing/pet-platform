package com.petplatform.boot.config;

import com.petplatform.common.CommonApiCodes;
import com.petplatform.user.biz.application.UserAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects the session-bearing /api/v1/c/** endpoints: resolves the MINIAPP Bearer into a {@code
 * MiniSessionView} request attribute before the controller runs. Anonymous auth routes (attempts,
 * wechat-login, phone-binding) and the convergent logout are not matched here. Authorization
 * headers and tokens are never logged.
 */
public final class CBearerSessionFilter extends OncePerRequestFilter {

  public static final String VIEW = "cSessionView";
  private static final Set<String> PROTECTED_EXACT =
      Set.of("/api/v1/c/pets", "/api/v1/c/profile", "/api/v1/c/auth/session");

  private final ObjectProvider<UserAuthService> services;

  public CBearerSessionFilter(ObjectProvider<UserAuthService> services) {
    this.services = services;
  }

  /** Authorization: Bearer extraction with the same shape rules as the admin filter. */
  public static String bearer(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ") || header.length() > 1100) return null;
    String token = header.substring(7);
    if (token.isBlank() || token.contains(" ")) return null;
    return token;
  }

  static boolean protectedPath(String path) {
    return PROTECTED_EXACT.contains(path)
        || path.startsWith("/api/v1/c/pets/")
        || path.equals("/api/v1/c/merchant-application-cities")
        || path.equals("/api/v1/c/private-assets")
        || path.equals("/api/v1/c/merchant-applications")
        || path.startsWith("/api/v1/c/merchant-applications/")
        || path.equals("/api/v1/merchant/agreement")
        || path.equals("/api/v1/merchant/agreement/consent")
        || path.equals("/api/v1/c/auth/merchant-memberships")
        || path.equals("/api/v1/merchant/auth/admission");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (!protectedPath(req.getRequestURI())) {
      chain.doFilter(req, res);
      return;
    }
    String token = bearer(req);
    try {
      if (token == null) throw new IllegalArgumentException("missing bearer");
      UserAuthService service = services.getIfAvailable();
      if (service == null) throw new IllegalArgumentException("auth disabled");
      req.setAttribute(VIEW, service.resolveSession(token));
    } catch (RuntimeException invalid) {
      res.setStatus(401);
      res.setContentType("application/json");
      res.setHeader("Cache-Control", "no-store, private");
      res.setHeader("Pragma", "no-cache");
      res.setHeader("X-Content-Type-Options", "nosniff");
      String success = merchantPath(req.getRequestURI()) ? "\"success\":false," : "";
      res.getWriter()
          .write(
              "{"
                  + success
                  + "\"code\":\""
                  + CommonApiCodes.UNAUTHORIZED
                  + "\","
                  + "\"message\":\"登录已失效，请重新登录\",\"data\":null,"
                  + "\"traceId\":"
                  + traceJson(req)
                  + "}");
      return;
    }
    chain.doFilter(req, res);
  }

  private static boolean merchantPath(String path) {
    return path.equals("/api/v1/c/merchant-application-cities")
        || path.equals("/api/v1/c/private-assets")
        || path.startsWith("/api/v1/c/merchant-applications")
        || path.startsWith("/api/v1/merchant/agreement");
  }

  private static String traceJson(HttpServletRequest req) {
    Object trace = req.getAttribute("cTraceId");
    String value = trace instanceof String text ? text : req.getHeader("X-Trace-Id");
    if (value == null || !value.matches("[A-Za-z0-9._:-]{1,64}"))
      value = java.util.UUID.randomUUID().toString();
    req.setAttribute("cTraceId", value);
    return "\"" + value + "\"";
  }
}
