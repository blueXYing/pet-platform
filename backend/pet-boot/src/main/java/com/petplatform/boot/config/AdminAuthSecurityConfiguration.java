package com.petplatform.boot.config;

import com.petplatform.admin.biz.application.AdminAuthService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AdminAuthProperties.class)
public class AdminAuthSecurityConfiguration {
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService noImplicitDefaultAccounts() {
    return name -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Account unavailable");
    };
  }

  @Bean
  @Order(1)
  SecurityFilterChain adminSecurity(
      HttpSecurity http,
      AdminAuthProperties p,
      ObjectProvider<AdminAuthService> services,
      @Value("${pet.merchant.application.enabled:false}") boolean merchantApplicationEnabled,
      @Value("${pet.private-assets.enabled:false}") boolean privateAssetsEnabled,
      @Value("${pet.service.command.enabled:false}") boolean serviceCommandEnabled)
      throws Exception {
    http.securityMatcher("/api/v1/admin/**")
        .csrf(c -> c.disable())
        .httpBasic(c -> c.disable())
        .formLogin(c -> c.disable())
        .logout(c -> c.disable());
    http.authorizeHttpRequests(
        a -> {
          if (p.isEnabled() && !p.isMaintenance()) {
            a.requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/admin/auth/attempts",
                    "/api/v1/admin/auth/login",
                    "/api/v1/admin/auth/captcha/challenges",
                    "/api/v1/admin/auth/captcha/verify",
                    "/api/v1/admin/auth/logout",
                    "/api/v1/admin/auth/activity")
                .permitAll();
            if (merchantApplicationEnabled) {
              a.requestMatchers("/api/v1/admin/merchant-applications/**").permitAll();
              a.requestMatchers("/api/v1/admin/merchant-applications").permitAll();
            }
            if (privateAssetsEnabled) {
              a.requestMatchers(HttpMethod.GET, "/api/v1/admin/private-asset-read-grants/*")
                  .permitAll();
            }
            if (serviceCommandEnabled) {
              // Service review (ADM-001 write slice): real admin session + action codes are
              // enforced by AdminBearerAuthenticationFilter and the controller.
              a.requestMatchers("/api/v1/admin/services", "/api/v1/admin/services/**").permitAll();
            }
            a.requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/admin/auth/session",
                    "/api/v1/admin/auth/permissions",
                    "/api/v1/admin/auth/attempts/*/result",
                    "/api/v1/admin/auth/attempts/*/requirements")
                .permitAll();
          }
          a.anyRequest().denyAll();
        });
    http.exceptionHandling(
        e ->
            e.accessDeniedHandler((req, res, error) -> reject(req, res, services))
                .authenticationEntryPoint((req, res, error) -> reject(req, res, services)));
    if (p.isEnabled())
      http.addFilterBefore(
          new AdminBearerAuthenticationFilter(services),
          UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  private static void reject(
      jakarta.servlet.http.HttpServletRequest req,
      jakarta.servlet.http.HttpServletResponse res,
      ObjectProvider<AdminAuthService> services)
      throws java.io.IOException {
    AdminAuthService service = services.getIfAvailable();
    if (service != null) service.recordRejected("AUTH_FORBIDDEN", req.getHeader("X-Request-Id"));
    res.setStatus(403);
    res.setContentType("application/json");
    res.setHeader("Cache-Control", "no-store, private");
    res.setHeader("Pragma", "no-cache");
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.getWriter()
        .write(
            "{"
                + (req.getRequestURI().startsWith("/api/v1/admin/merchant-applications")
                        || req.getRequestURI()
                            .startsWith("/api/v1/admin/private-asset-read-grants/")
                    ? "\"success\":false,"
                    : "")
                + "\"code\":\"COMMON_FORBIDDEN\",\"message\":\"Forbidden\",\"data\":null,\"traceId\":\""
                + com.petplatform.boot.adapter.web.admin.AdminAuthController.trace(req)
                + "\"}");
  }

  /**
   * Adding an admin chain must not accidentally leave all other, unimplemented routes unprotected.
   * Order 3: the C-end chain (order 2) was inserted ahead of this catch-all without changing it.
   */
  @Bean
  @Order(3)
  SecurityFilterChain otherRoutes(HttpSecurity http) throws Exception {
    http.csrf(c -> c.disable())
        .httpBasic(c -> c.disable())
        .formLogin(c -> c.disable())
        .logout(c -> c.disable())
        .authorizeHttpRequests(
            a -> a.requestMatchers("/actuator/health").permitAll().anyRequest().denyAll());
    return http.build();
  }
}
