package com.petplatform.boot.config;

import com.petplatform.admin.biz.application.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

/** Deliberately does not log Authorization, cookies or request bodies. */
public final class AdminBearerAuthenticationFilter extends OncePerRequestFilter {
  public static final String VIEW = AdminBearerAuthenticationFilter.class.getName() + ".view";
  private final ObjectProvider<AdminAuthService> services;

  public AdminBearerAuthenticationFilter(ObjectProvider<AdminAuthService> services) {
    this.services = services;
  }

  public static String bearer(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ") || header.length() > 1100)
      throw AdminAuthFailure.unauthorized();
    String token = header.substring(7);
    if (token.isBlank() || token.contains(" ")) throw AdminAuthFailure.unauthorized();
    return token;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String trace = com.petplatform.boot.adapter.web.admin.AdminAuthController.trace(req);
    AdminAuthService.bindTrace(trace);
    try {
      String path = req.getRequestURI();
      // A read-grant token is a path credential. It is used for routing only and never logged.
      if (path.equals("/api/v1/admin/auth/session")
          || path.equals("/api/v1/admin/auth/permissions")
          || path.equals("/api/v1/admin/auth/activity")
          || path.equals("/api/v1/admin/merchant-applications")
          || path.startsWith("/api/v1/admin/merchant-applications/")
          || path.startsWith("/api/v1/admin/private-asset-read-grants/")
          || path.equals("/api/v1/admin/services")
          || path.startsWith("/api/v1/admin/services/")) {
        try {
          AdminAuthService service = services.getIfAvailable();
          if (service == null) throw AdminAuthFailure.unavailable();
          req.setAttribute(VIEW, service.resolveSession(bearer(req)));
        } catch (RuntimeException e) {
          AdminAuthFailure a = e instanceof AdminAuthFailure f ? f : AdminAuthFailure.unavailable();
          AdminAuthService service = services.getIfAvailable();
          if (service != null)
            service.recordRejected("AUTH_CREDENTIAL_REJECTED", req.getHeader("X-Request-Id"));
          res.setStatus(a.status());
          res.setContentType("application/json");
          res.setHeader("Cache-Control", "no-store, private");
          res.setHeader("Pragma", "no-cache");
          res.setHeader("X-Content-Type-Options", "nosniff");
          res.getWriter()
              .write(
                  "{"
                      + (path.startsWith("/api/v1/admin/merchant-applications")
                              || path.startsWith("/api/v1/admin/private-asset-read-grants/")
                              || path.startsWith("/api/v1/admin/services")
                          ? "\"success\":false,"
                          : "")
                      + "\"code\":\""
                      + a.code()
                      + "\",\"message\":\"Authentication unavailable or"
                      + " invalid\",\"data\":null,\"traceId\":\""
                      + trace
                      + "\"}");
          return;
        }
      }
      chain.doFilter(req, res);
    } finally {
      AdminAuthService.clearTrace();
    }
  }
}
