package com.petplatform.boot.config;

import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.admin.api.query.AdminSessionQueryApi;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.order.api.query.OrderQueryApi;
import com.petplatform.review.biz.apiimpl.ReviewApiImpl;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.biz.application.UserAuthService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * REV-001/REV-002 review assembly (HTTP contract 10 §3.14 + 56号 / internal 07 §7.7/§14). The
 * kernel rides the shared main DataSource: the 06号 review/review_appeal tables plus the 14号
 * command_idempotency storage (same shared-table precedent as the schedule write side). The
 * kernel cannot run without the C session switch's real order query slice (OrderQueryApiImpl),
 * and the C HTTP face cannot run without the kernel. The REV-002 appeal faces additionally
 * need the store guard (schedule protection), the MER order authority and both real session
 * domains (C MINIAPP for the OWNER face, ADMIN_WEB for the operator face) — all default off,
 * exactly like the sibling slices.
 */
@Configuration(proxyBeanMethods = false)
public class ReviewConfiguration {

    @Bean
    Object reviewSwitchValidation(Environment e) {
        boolean enabled = e.getProperty("pet.review.enabled", Boolean.class, false);
        boolean http = e.getProperty("pet.review.http.enabled", Boolean.class, false);
        boolean dependencies = e.getProperty("pet.auth.c.enabled", Boolean.class, false);
        boolean appeal = e.getProperty("pet.review.appeal.enabled", Boolean.class, false);
        boolean appealHttp = e.getProperty("pet.review.appeal.http.enabled", Boolean.class, false);
        boolean appealDependencies = e.getProperty("pet.schedule.protection.enabled", Boolean.class, false)
                && e.getProperty("pet.auth.admin.enabled", Boolean.class, false);
        if (http && !enabled || enabled && !dependencies)
            throw new IllegalStateException(
                    "Review kernel requires the real C session and order query dependencies");
        if (appealHttp && !appeal || appeal && (!enabled || !dependencies || !appealDependencies))
            throw new IllegalStateException(
                    "Review appeal requires the review kernel, the store guard and both real"
                            + " session domains (pet.schedule.protection / pet.auth.admin)");
        return new Object();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "pet.review.enabled", havingValue = "true")
    static class Runtime {
        /** The apiimpl facade keeps review persistence inside pet-review-biz (ARCH-002). */
        @Bean
        ReviewApiImpl reviewApi(DataSource source, SnowflakeIdGenerator ids, OrderQueryApi orders,
                ObjectProvider<Clock> clocks,
                ObjectProvider<ScheduleCapacityGuardApi> guards,
                ObjectProvider<ReviewAppealAuthorityAdapter> appealAuthority) {
            // Same UTC-clock discipline as the sibling kernels; system UTC when no shared
            // clock bean is assembled. The appeal guard/authority beans exist only under
            // pet.review.appeal.enabled — without them the appeal faces fail closed (503).
            return new ReviewApiImpl(source, ids, orders, clocks.getIfAvailable(Clock::systemUTC),
                    guards.getIfAvailable(), appealAuthority.getIfAvailable());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "pet.review.appeal.enabled", havingValue = "true")
    static class AppealRuntime {
        /** Contract56 port-side authority: MER OWNER/admin action checks over real sessions.
         *  DependsOn keeps the switch validation first so an incomplete dependency stack
         *  always surfaces as the gate message, never as a bean-wiring accident. */
        @Bean(name = "reviewAppealAuthority")
        @org.springframework.context.annotation.DependsOn("reviewSwitchValidation")
        ReviewAppealAuthorityAdapter reviewAppealAuthority(DataSource source,
                ScheduleCapacityGuardApi guard, UserAuthService users,
                AdminSessionQueryApi sessions, AdminAuthorizationQueryApi admins) {
            return new ReviewAppealAuthorityAdapter(source, guard, users, sessions, admins);
        }
    }
}
