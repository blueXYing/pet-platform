package com.petplatform.boot.config;

import com.petplatform.user.biz.application.UserAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security chain for /api/v1/c/** and /api/v1/merchant/**: all implemented C-end and merchant
 * routes open together with {@code pet.auth.c.enabled}; session and role checks stay in
 * {@link CBearerSessionFilter} and each domain kernel, and unregistered routes remain denied.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class CSessionSecurityConfiguration {

  @Bean
  @Order(2)
  SecurityFilterChain cSecurity(
      HttpSecurity http,
      CAuthProperties p,
      ObjectProvider<UserAuthService> services)
      throws Exception {
    http.securityMatcher("/api/v1/c/**", "/api/v1/merchant/**")
        .csrf(c -> c.disable())
        .httpBasic(c -> c.disable())
        .formLogin(c -> c.disable())
        .logout(c -> c.disable());
    http.authorizeHttpRequests(
        a -> {
          if (p.isEnabled()) {
            // Aftersale C & M routes
            a.requestMatchers(HttpMethod.GET,
                "/api/v1/c/aftersale-options",
                "/api/v1/c/orders/*/aftersale-eligibility",
                "/api/v1/c/aftersales", "/api/v1/c/aftersales/*",
                "/api/v1/merchant/aftersales", "/api/v1/merchant/aftersales/*",
                "/api/v1/c/aftersale-evidence-read-grants/*",
                "/api/v1/merchant/aftersale-evidence-read-grants/*").permitAll();
            a.requestMatchers(HttpMethod.POST,
                "/api/v1/c/orders/*/aftersales", "/api/v1/c/aftersale-evidence-assets",
                "/api/v1/c/aftersales/*/evidence", "/api/v1/c/aftersales/*/withdraw",
                "/api/v1/merchant/aftersales/*/evidence", "/api/v1/merchant/aftersales/*/opinion",
                "/api/v1/c/aftersales/*/evidence-batches/*/assets/*/read-grants",
                "/api/v1/merchant/aftersales/*/evidence-batches/*/assets/*/read-grants").permitAll();
            // Auth, profile, pets & health records
            a.requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/c/auth/attempts",
                    "/api/v1/c/auth/wechat-login",
                    "/api/v1/c/auth/logout",
                    "/api/v1/c/account/phone-binding")
                .permitAll();
            a.requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/c/auth/session",
                    "/api/v1/c/pets",
                    "/api/v1/c/pets/*",
                    "/api/v1/c/pets/*/vaccination-records",
                    "/api/v1/c/pets/*/deworming-records",
                    "/api/v1/c/profile")
                .permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/c/pets",
                    "/api/v1/c/pets/*/vaccination-records",
                    "/api/v1/c/pets/*/deworming-records",
                    "/api/v1/c/profile").permitAll();
            a.requestMatchers(HttpMethod.PUT, "/api/v1/c/pets/*", "/api/v1/c/profile").permitAll();
            a.requestMatchers(HttpMethod.DELETE, "/api/v1/c/pets/*").permitAll();
            // Address book
            a.requestMatchers(
                    HttpMethod.GET, "/api/v1/c/addresses", "/api/v1/c/addresses/*").permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/c/addresses").permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/c/addresses/*/set-default").permitAll();
            a.requestMatchers(HttpMethod.PUT, "/api/v1/c/addresses/*").permitAll();
            a.requestMatchers(HttpMethod.DELETE, "/api/v1/c/addresses/*").permitAll();
            // Notifications & preferences
            a.requestMatchers("/api/v1/c/notifications", "/api/v1/c/notifications/**").permitAll();
            a.requestMatchers("/api/v1/c/notification-preferences").permitAll();
            // Orders, reschedule, verification, refund applications
            a.requestMatchers(HttpMethod.GET, "/api/v1/c/orders", "/api/v1/c/orders/*").permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/c/orders", "/api/v1/c/orders/*/payments")
                .permitAll();
            a.requestMatchers(HttpMethod.GET, "/api/v1/c/orders/*/verification-code").permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/c/orders/*/verification-code").permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/c/orders/*/refund-applications").permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/c/orders/*/reschedule").permitAll();
            // Coupons & points
            a.requestMatchers(HttpMethod.GET,
                    "/api/v1/c/coupons",
                    "/api/v1/c/coupons/*",
                    "/api/v1/c/points/balance",
                    "/api/v1/c/points/ledger",
                    "/api/v1/c/points/sign-in/status",
                    "/api/v1/c/points/tasks").permitAll();
            a.requestMatchers(HttpMethod.POST,
                    "/api/v1/c/points/sign-in",
                    "/api/v1/c/points/claims",
                    "/api/v1/c/points/referral-bindings").permitAll();
            // Catalog, stores, cities & schedule availability
            a.requestMatchers("/api/v1/c/stores/*/services").permitAll();
            a.requestMatchers(
                    "/api/v1/c/services", "/api/v1/c/services/*", "/api/v1/c/service-categories")
                .permitAll();
            a.requestMatchers(HttpMethod.GET,
                    "/api/v1/c/stores/featured",
                    "/api/v1/c/stores",
                    "/api/v1/c/stores/*",
                    "/api/v1/c/cities",
                    "/api/v1/c/services/*/availability").permitAll();
            // Merchant admission, applications, agreement & private assets
            a.requestMatchers(HttpMethod.GET, "/api/v1/c/merchant-application-cities").permitAll();
            a.requestMatchers("/api/v1/c/merchant-applications/**").permitAll();
            a.requestMatchers("/api/v1/c/merchant-applications").permitAll();
            a.requestMatchers("/api/v1/merchant/agreement", "/api/v1/merchant/agreement/consent")
                .permitAll();
            a.requestMatchers(
                    "/api/v1/c/auth/merchant-memberships",
                    "/api/v1/merchant/auth/admission")
                .permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/c/private-assets").permitAll();
            // Merchant workbench: services, staff, staff-members, schedule
            a.requestMatchers("/api/v1/merchant/services", "/api/v1/merchant/services/**")
                .permitAll();
            a.requestMatchers(HttpMethod.GET, "/api/v1/merchant/service-categories").permitAll();
            a.requestMatchers(HttpMethod.GET, "/api/v1/merchant/staff", "/api/v1/merchant/staff/*")
                .permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/merchant/staff", "/api/v1/merchant/staff/*/enable")
                .permitAll();
            a.requestMatchers(HttpMethod.PUT, "/api/v1/merchant/staff/*").permitAll();
            a.requestMatchers("/api/v1/merchant/staff-members", "/api/v1/merchant/staff-members/**")
                .permitAll();
            a.requestMatchers("/api/v1/c/staff/invitations", "/api/v1/c/staff/invitations/**")
                .permitAll();
            a.requestMatchers(HttpMethod.GET,
                    "/api/v1/merchant/stores/*/availability-windows",
                    "/api/v1/merchant/stores/*/availability-windows/*",
                    "/api/v1/merchant/staff/*/availability-windows",
                    "/api/v1/merchant/staff/*/availability-windows/*",
                    "/api/v1/merchant/staff/*/service-capabilities",
                    "/api/v1/merchant/stores/*/booking-window",
                    "/api/v1/merchant/stores/*/week-template")
                .permitAll();
            a.requestMatchers(HttpMethod.POST,
                    "/api/v1/merchant/stores/*/availability-windows",
                    "/api/v1/merchant/stores/*/availability-windows/*",
                    "/api/v1/merchant/staff/*/availability-windows",
                    "/api/v1/merchant/staff/*/availability-windows/*")
                .permitAll();
            a.requestMatchers(HttpMethod.PUT,
                    "/api/v1/merchant/stores/*/availability-windows/*",
                    "/api/v1/merchant/staff/*/availability-windows/*",
                    "/api/v1/merchant/staff/*/service-capabilities",
                    "/api/v1/merchant/stores/*/booking-window",
                    "/api/v1/merchant/stores/*/week-template")
                .permitAll();
            // Merchant orders, verification, refunds, store profile, stats, customers
            a.requestMatchers(HttpMethod.POST, "/api/v1/merchant/orders/*/verification").permitAll();
            a.requestMatchers(HttpMethod.GET, "/api/v1/merchant/orders").permitAll();
            a.requestMatchers(HttpMethod.POST,
                    "/api/v1/merchant/orders/*/confirm",
                    "/api/v1/merchant/orders/*/reject").permitAll();
            a.requestMatchers(HttpMethod.GET,
                    "/api/v1/merchant/store-profile",
                    "/api/v1/merchant/store-profile/avatar-url").permitAll();
            a.requestMatchers(HttpMethod.PUT, "/api/v1/merchant/store-profile").permitAll();
            a.requestMatchers(HttpMethod.GET, "/api/v1/merchant/stats/**").permitAll();
            a.requestMatchers(HttpMethod.GET,
                    "/api/v1/merchant/customers",
                    "/api/v1/merchant/customers/*").permitAll();
            a.requestMatchers(HttpMethod.PUT, "/api/v1/merchant/customers/*/note").permitAll();
            a.requestMatchers(HttpMethod.GET,
                    "/api/v1/merchant/refund-applications",
                    "/api/v1/merchant/refund-applications/*").permitAll();
            a.requestMatchers(HttpMethod.POST,
                    "/api/v1/merchant/refund-applications/*/approve",
                    "/api/v1/merchant/refund-applications/*/reject").permitAll();
            a.requestMatchers(HttpMethod.GET, "/api/v1/merchant/verifications").permitAll();
            // Reviews & appeals
            a.requestMatchers(HttpMethod.GET, "/api/v1/c/orders/*/review-eligibility").permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/c/orders/*/reviews").permitAll();
            a.requestMatchers(HttpMethod.GET,
                    "/api/v1/merchant/reviews", "/api/v1/merchant/reviews/*").permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/merchant/reviews/*/appeal").permitAll();
            // Favorites, footprints, encyclopedia, community, marketing, account deletion, feedback
            a.requestMatchers(HttpMethod.GET,
                    "/api/v1/c/favorites",
                    "/api/v1/c/favorites/*/*",
                    "/api/v1/c/footprints").permitAll();
            a.requestMatchers(HttpMethod.POST,
                    "/api/v1/c/favorites/*/*",
                    "/api/v1/c/footprints/*/*").permitAll();
            a.requestMatchers(HttpMethod.DELETE,
                    "/api/v1/c/favorites/*/*",
                    "/api/v1/c/footprints/*").permitAll();
            a.requestMatchers(HttpMethod.GET,
                    "/api/v1/c/encyclopedia/categories",
                    "/api/v1/c/encyclopedia/breeds",
                    "/api/v1/c/encyclopedia/breeds/*").permitAll();
            a.requestMatchers("/api/v1/c/community/**").permitAll();
            // Contract-79 §5 adds the per-store merchant coupon list (79 号): an anonymous browse
            // read like the campaign detail; the controller attaches the session when a valid
            // bearer is carried, so the claimed state follows the reader.
            a.requestMatchers(HttpMethod.GET,
                    "/api/v1/c/marketing/**",
                    "/api/v1/c/coupon-campaigns",
                    "/api/v1/c/coupon-campaigns/*").permitAll();
            a.requestMatchers(HttpMethod.POST,
                    "/api/v1/c/coupon-campaigns/*/claims").permitAll();
            a.requestMatchers("/api/v1/merchant/marketing/**").permitAll();
            // CCR-PAY-CENTER-001 Stage 7 (contract 80 §5): merchant OWNER-only wallet & read-only
            // receiver status routes (Bearer verified by CBearerSessionFilter; OWNER enforced in controller;
            // controllers are wired directly under pet.auth.c.enabled + pet.payment.enabled).
            a.requestMatchers(HttpMethod.GET,
                    "/api/v1/merchant/settlement/receiver",
                    "/api/v1/merchant/wallet",
                    "/api/v1/merchant/wallet/withdrawals").permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/merchant/wallet/withdrawals").permitAll();
          }
          a.anyRequest().denyAll();
        });
    http.exceptionHandling(
        e ->
            e.accessDeniedHandler((req, res, error) -> reject(req, res))
                .authenticationEntryPoint((req, res, error) -> reject(req, res)));
    if (p.isEnabled()) {
      // The custom filter enforces the MINIAPP Bearer on the business routes above.
      http.addFilterBefore(
          new CBearerSessionFilter(services), UsernamePasswordAuthenticationFilter.class);
    }
    return http.build();
  }

  private static void reject(HttpServletRequest req, HttpServletResponse res)
      throws java.io.IOException {
    res.setStatus(403);
    res.setContentType("application/json");
    res.setHeader("Cache-Control", "no-store, private");
    res.setHeader("Pragma", "no-cache");
    res.setHeader("X-Content-Type-Options", "nosniff");
    String trace = req.getHeader("X-Trace-Id");
    if (trace == null || !trace.matches("[A-Za-z0-9._:-]{1,64}")) {
      trace = java.util.UUID.randomUUID().toString().replace("-", "");
    }
    String path = req.getRequestURI();
    String prefix =
        path.startsWith("/api/v1/c/merchant-applications")
                || path.startsWith("/api/v1/c/private-assets")
                || path.equals("/api/v1/c/merchant-application-cities")
                || path.startsWith("/api/v1/merchant/agreement")
            ? "\"success\":false,"
            : "";
    res.getWriter()
        .write(
            "{"
                + prefix
                + "\"code\":\"COMMON_FORBIDDEN\",\"message\":\"Forbidden\",\"data\":null,\"traceId\":\""
                + trace
                + "\"}");
  }
}
