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

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
    static class Runtime {
        /** The apiimpl facade keeps review persistence inside pet-review-biz (ARCH-002). */
        @Bean
        ReviewApiImpl reviewApi(DataSource source, SnowflakeIdGenerator ids, OrderQueryApi orders,
                ObjectProvider<Clock> clocks,
                ObjectProvider<ScheduleCapacityGuardApi> guards,
                ObjectProvider<ReviewAppealAuthorityAdapter> appealAuthority) {
            return new ReviewApiImpl(source, ids, orders, clocks.getIfAvailable(Clock::systemUTC),
                    guards.getIfAvailable(), appealAuthority.getIfAvailable(),
                    new com.petplatform.user.biz.apiimpl.UserQueryApiImpl(source));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "pet", name = {"auth.c.enabled", "auth.admin.enabled"}, havingValue = "true")
    static class AppealRuntime {
        @Bean(name = "reviewAppealAuthority")
        ReviewAppealAuthorityAdapter reviewAppealAuthority(DataSource source,
                ScheduleCapacityGuardApi guard, UserAuthService users,
                AdminSessionQueryApi sessions, AdminAuthorizationQueryApi admins) {
            return new ReviewAppealAuthorityAdapter(source, guard, users, sessions, admins);
        }
    }
}
