package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.order.api.query.OrderQueryApi;
import com.petplatform.review.biz.apiimpl.ReviewApiImpl;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * REV-001 review assembly (HTTP contract 10 §3.14 + internal 07 §7.7/§14). The kernel rides
 * the shared main DataSource: the 06号 review table plus the 14号 command_idempotency storage
 * (same shared-table precedent as the schedule write side). The kernel cannot run without the
 * C session switch's real order query slice (OrderQueryApiImpl), and the C HTTP face cannot
 * run without the kernel — both stay default off, exactly like the sibling C write slices.
 */
@Configuration(proxyBeanMethods = false)
public class ReviewConfiguration {

    @Bean
    Object reviewSwitchValidation(Environment e) {
        boolean enabled = e.getProperty("pet.review.enabled", Boolean.class, false);
        boolean http = e.getProperty("pet.review.http.enabled", Boolean.class, false);
        boolean dependencies = e.getProperty("pet.auth.c.enabled", Boolean.class, false);
        if (http && !enabled || enabled && !dependencies)
            throw new IllegalStateException(
                    "Review kernel requires the real C session and order query dependencies");
        return new Object();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "pet.review.enabled", havingValue = "true")
    static class Runtime {
        /** The apiimpl facade keeps review persistence inside pet-review-biz (ARCH-002). */
        @Bean
        ReviewApiImpl reviewApi(DataSource source, SnowflakeIdGenerator ids, OrderQueryApi orders,
                ObjectProvider<Clock> clocks) {
            // Same UTC-clock discipline as the sibling kernels; system UTC when no shared
            // clock bean is assembled.
            return new ReviewApiImpl(source, ids, orders, clocks.getIfAvailable(Clock::systemUTC));
        }
    }
}
