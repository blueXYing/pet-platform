package com.petplatform.boot.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Contract 80 §6 & §7 HTTP exposure configuration (CCR-PAY-CENTER-001 Stage 7-BE).
 *
 * <p>Default off ({@code pet.payment.settlement.http.enabled=false}). Enabling requires
 * {@code pet.payment.settlement.enabled=true}. Registers a dedicated public {@link SecurityFilterChain}
 * for the 4 Lakala 1009-signed callback POST endpoints while keeping every other path denied.
 */
@Configuration(proxyBeanMethods = false)
public class PaymentSettlementHttpConfiguration {
    static final String REQUIRES_SETTLEMENT =
            "Payment settlement HTTP routes require pet.payment.settlement.enabled=true";

    @Bean
    InitializingBean paymentSettlementHttpConfigurationGuard(
            @Value("${pet.payment.settlement.enabled:false}") boolean settlementEnabled,
            @Value("${pet.payment.settlement.http.enabled:false}") boolean httpEnabled) {
        return () -> check(settlementEnabled, httpEnabled);
    }

    static void check(boolean settlementEnabled, boolean httpEnabled) {
        if (httpEnabled && !settlementEnabled) {
            throw new IllegalStateException(REQUIRES_SETTLEMENT);
        }
    }

    @Bean
    @Order(0)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnExpression(
            "'${pet.payment.settlement.http.enabled:false}' == 'true' and '${pet.payment.settlement.enabled:false}' == 'true'")
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
