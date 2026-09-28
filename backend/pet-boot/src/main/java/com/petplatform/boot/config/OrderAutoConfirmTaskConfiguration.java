package com.petplatform.boot.config;

import com.petplatform.order.api.query.OrderAutoConfirmTaskInspectionApi;
import com.petplatform.order.biz.apiimpl.OrderAutoConfirmTaskInspectionApiImpl;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** B-only task preparation. Confirmation and mutating recovery require the separately reviewed A contract. */
@Configuration(proxyBeanMethods = false)
public class OrderAutoConfirmTaskConfiguration {
    @Bean InitializingBean autoConfirmTaskConfigurationGuard(
            @Value("${pet.order.auto-confirm.enabled:false}") boolean enabled,
            @Value("${pet.payment.foundation.enabled:false}") boolean paymentEnabled,
            @Value("${pet.order.auto-confirm.worker.enabled:false}") boolean worker,
            @Value("${pet.order.auto-confirm.repair.enabled:false}") boolean repair) {
        return () -> {
            if (worker || repair)
                throw new IllegalStateException("Auto-confirm execution and repair require contract A implementation");
            if (enabled && !paymentEnabled)
                throw new IllegalStateException("Auto-confirm task production requires payment foundation");
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "pet.order.auto-confirm.inspection", name = "enabled", havingValue = "true")
    OrderAutoConfirmTaskInspectionApi orderAutoConfirmTaskInspectionApi(DataSource source) {
        return new OrderAutoConfirmTaskInspectionApiImpl(source);
    }
}
