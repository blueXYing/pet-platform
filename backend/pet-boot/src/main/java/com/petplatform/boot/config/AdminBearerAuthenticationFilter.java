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
    String chainTrace = org.slf4j.MDC.get(TraceContextFilter.TRACE_MDC_KEY);
    if (chainTrace != null) trace = chainTrace;
    AdminAuthService.bindTrace(trace);
    try {
      String path = req.getRequestURI();
      // A read-grant token is a path credential. It is used for routing only and never logged.
      if (path.equals("/api/v1/admin/auth/session")
          || path.equals("/api/v1/admin/aftersales") || path.startsWith("/api/v1/admin/aftersales/")
          || path.startsWith("/api/v1/admin/aftersale-evidence-read-grants/")
          || path.equals("/api/v1/admin/auth/permissions")
          || path.equals("/api/v1/admin/auth/activity")
          || path.equals("/api/v1/admin/merchant-applications")
          || path.startsWith("/api/v1/admin/merchant-applications/")
          || path.startsWith("/api/v1/admin/private-asset-read-grants/")
          || path.equals("/api/v1/admin/services")
          || path.startsWith("/api/v1/admin/services/")
          // Service category dictionary (CCR-SVC-CAT-001 B-SVC-02): ADMIN_WEB Bearer is
          // resolved here; service.category.read/manage are re-proved by the controller.
          || path.equals("/api/v1/admin/service-categories")
          || path.startsWith("/api/v1/admin/service-categories/")
          // Review appeal governance (contract 56 / REV-002): ADMIN_WEB Bearer enforced;
          // the review.appeal.* action codes are re-checked by the kernel per call/replay.
          || path.equals("/api/v1/admin/review-appeals")
          || path.startsWith("/api/v1/admin/review-appeals/")
          // Points task config (contract 58 / 32号 ruling second batch): ADMIN_WEB Bearer;
          // points.task.config.read/write are re-checked by the controller per request.
          || path.equals("/api/v1/admin/points-task-config")
          // Platform trade read face (contract 63 / ADM-002+A-003): ADMIN_WEB Bearer;
          // order.*/refund.read action codes and the ALL data scope are re-checked by the
          // controller and each kernel per request.
          || path.startsWith("/api/v1/admin/trade/")
          // Platform governance read face (contract 64 / ADM-001 gap slice): ADMIN_WEB Bearer;
          // merchant.read/schedule.read/merchant.staff.read action codes and the ALL data
          // scope are re-checked by the controller and each kernel per request.
          || path.startsWith("/api/v1/admin/governance/")
          // Contract-63 v0.2 disposal writes (user ruling 2026-10-15, doc 33, FA-1): the same
          // face switch as the trade reads; refund.channel.query / refund.retry /
          // order.abnormal.close / merchant.disable are re-checked inside each kernel's own
          // commit. These four routes own their subtrees — no other admin face answers there.
          || path.startsWith("/api/v1/admin/refunds/")
          || (path.startsWith("/api/v1/admin/orders/") && path.endsWith("/abnormal-close"))
          || (path.startsWith("/api/v1/admin/merchants/") && path.endsWith("/disable"))
          // Platform ops console face (contract 65 / A-005, FA-3): ADMIN_WEB Bearer; the
          // encyclopedia.* / breed.* / coupon.* / points.ledger.read / system.parameter.read
          // action codes and the ALL data scope are re-checked by the controller and each
          // kernel per request. Without these entries the face answered 401 behind the chain
          // permit.
          || path.startsWith("/api/v1/admin/encyclopedia/")
          || path.startsWith("/api/v1/admin/coupon-templates")
          || path.startsWith("/api/v1/admin/coupon-instances")
          || path.equals("/api/v1/admin/points/ledger")
          || path.equals("/api/v1/admin/settings/runtime")
          // Contract-71/72 marketing center and coupon campaigns: ADMIN_WEB Bearer is
          // resolved here; the marketing/coupon kernels re-prove their action codes.
          || path.startsWith("/api/v1/admin/marketing/")
          || path.startsWith("/api/v1/admin/coupon-campaigns")
          // CCR-COM-001 community moderation: ADMIN_WEB Bearer enforced; action codes are
          // re-proved by the community kernel per call.
          || path.startsWith("/api/v1/admin/community/")) {
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
                              || path.startsWith("/api/v1/admin/service-categories")
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
