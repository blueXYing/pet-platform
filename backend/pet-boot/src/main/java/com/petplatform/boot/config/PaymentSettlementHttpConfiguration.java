package com.petplatform.boot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Contract 80 §6 HTTP callback exposure configuration (CCR-PAY-CENTER-001 Stage 7-BE).
 *
 * <p>Wired directly under the unified payment base switch {@code pet.payment.enabled=true}
 * (per PR #234 single-switch architecture). Registers a dedicated public {@link SecurityFilterChain}
 * for the 4 Lakala 1009-signed callback POST endpoints while keeping every other path denied.
 */
@Configuration(proxyBeanMethods = false)
public class PaymentSettlementHttpConfiguration {

    @Bean
    @Order(0)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "pet.payment", name = "enabled", havingValue = "true")
    SecurityFilterChain lakalaCallbackSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/callbacks/lakala/**")
                .csrf(c -> c.disable())
                .httpBasic(c -> c.disable())
                .formLogin(c -> c.disable())
                .logout(c -> c.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/callbacks/lakala/pay",
                                "/api/v1/callbacks/lakala/separate",
                                "/api/v1/callbacks/lakala/withdrawals",
                                "/api/v1/callbacks/lakala/ledger")
                        .permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
