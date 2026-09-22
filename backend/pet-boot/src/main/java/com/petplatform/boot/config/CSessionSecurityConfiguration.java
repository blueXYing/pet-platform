package com.petplatform.boot.config;

import com.petplatform.user.biz.application.UserAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
 * Security chain for /api/v1/c/**: anonymous auth routes plus the session-filtered business routes
 * when C auth is enabled; everything else stays denied so no unimplemented route can be reached
 * accidentally. The chain only exists when the C slice is enabled — disabled, the /api/v1/c/**
 * surface keeps falling through to the global catch-all deny (403) like before.
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
      ObjectProvider<UserAuthService> services,
      @Value("${pet.merchant.application.enabled:false}") boolean merchantApplicationEnabled,
      @Value("${pet.private-assets.enabled:false}") boolean privateAssetsEnabled)
      throws Exception {
    http.securityMatcher("/api/v1/c/**", "/api/v1/merchant/**")
        .csrf(c -> c.disable())
        .httpBasic(c -> c.disable())
        .formLogin(c -> c.disable())
        .logout(c -> c.disable());
    http.authorizeHttpRequests(
        a -> {
          if (p.isEnabled()) {
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
                    "/api/v1/c/profile")
                .permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/v1/c/pets", "/api/v1/c/profile").permitAll();
            // Inbox reads (CCR-W2-NOTIFICATION-001): MINIAPP Bearer enforced by the filter.
            a.requestMatchers("/api/v1/c/notifications", "/api/v1/c/notifications/**")
                .permitAll();
            // Service catalog (CCR-W2-API-001 service domain): MINIAPP Bearer via the filter.
            a.requestMatchers("/api/v1/c/stores/*/services").permitAll();
            a.requestMatchers("/api/v1/c/services/*").permitAll();
            a.requestMatchers(HttpMethod.PUT, "/api/v1/c/pets/*", "/api/v1/c/profile").permitAll();
            a.requestMatchers(HttpMethod.DELETE, "/api/v1/c/pets/*").permitAll();
            if (merchantApplicationEnabled) {
              a.requestMatchers(HttpMethod.GET, "/api/v1/c/merchant-application-cities")
                  .permitAll();
              a.requestMatchers("/api/v1/c/merchant-applications/**").permitAll();
              a.requestMatchers("/api/v1/c/merchant-applications").permitAll();
              a.requestMatchers("/api/v1/merchant/agreement", "/api/v1/merchant/agreement/consent")
                  .permitAll();
              // Admission surfaces (CCR-W2-ADMISSION-001): MINIAPP Bearer is enforced by the
              // CBearerSessionFilter protectedPath list, not by permitAll itself.
              a.requestMatchers(
                      "/api/v1/c/auth/merchant-memberships",
                      "/api/v1/merchant/auth/admission")
                  .permitAll();
            }
            if (privateAssetsEnabled) {
              a.requestMatchers(HttpMethod.POST, "/api/v1/c/private-assets").permitAll();
            }
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
      trace = java.util.UUID.randomUUID().toString();
    }
    String success =
        req.getRequestURI().equals("/api/v1/c/merchant-application-cities")
                || req.getRequestURI().equals("/api/v1/c/private-assets")
                || req.getRequestURI().startsWith("/api/v1/c/merchant-applications")
                || req.getRequestURI().startsWith("/api/v1/merchant/agreement")
            ? "\"success\":false,"
            : "";
    res.getWriter()
        .write(
            "{"
                + success
                + "\"code\":\"COMMON_FORBIDDEN\",\"message\":\"Forbidden\","
                + "\"data\":null,\"traceId\":\""
                + trace
                + "\"}");
  }
}
