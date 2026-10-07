package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.event.api.IntegrationEventPublisher;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffMemberApiImpl;
import com.petplatform.merchant.biz.application.ApplicationReviewFactsReader;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts;
import com.petplatform.merchant.biz.application.StaffLoginPhonePort;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.user.api.query.UserPhoneVerificationApi;
import com.petplatform.user.api.query.UserIdQuery;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 54 staff binding (D1-a invite-confirm; D2 verify-only catalog), default off. Requires
 * the shared schedule store-guard bean (pet.schedule.protection.enabled): guarded binding
 * commands fail closed without it, matching the contract-52 kernel wiring precedent.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.merchant.staff-member", name = "enabled", havingValue = "true")
public class MerchantStaffMemberConfiguration {
    @Bean
    MerchantStaffMemberApiImpl merchantStaffMemberApi(DataSource source, SnowflakeIdGenerator ids,
            ObjectProvider<ApplicationReviewFactsReader> applications,
            ObjectProvider<ApplicationValidationPorts.ProtectedValuePort> protection,
            ObjectProvider<UserPhoneVerificationApi> phones, ScheduleCapacityGuardApi guard,
            ObjectProvider<Clock> clock,
            ObjectProvider<IntegrationEventPublisher> events) {
        // Contract 54 §1/§7: single and batch phone equality both stay inside the user module —
        // only booleans / matched merchant-owned candidate values cross the boundary.
        StaffLoginPhonePort loginPhones = new StaffLoginPhonePort() {
            @Override
            public boolean matchesSessionUserPhone(long userId, String phone) {
                return phones.getObject().hasVerifiedPhone(
                        new UserIdQuery(Long.toUnsignedString(userId)), phone);
            }

            @Override
            public java.util.Set<String> matchSessionUserPhones(long userId,
                    java.util.Collection<String> candidates) {
                return phones.getObject().verifiedPhonesEqualTo(
                        new UserIdQuery(Long.toUnsignedString(userId)), candidates);
            }
        };
        // NTF slice: the outbox publisher is optional — with pet.outbox.enabled=false (default)
        // the binding keeps its pre-notification behavior and emits no lifecycle events.
        return new MerchantStaffMemberApiImpl(source, ids,
                applications.getIfAvailable(() -> MerchantStaffMemberApiImpl.unavailableApplicationFacts()),
                protection.getIfAvailable(() -> MerchantStaffMemberApiImpl.unavailableProtection()),
                loginPhones, guard, clock.getIfAvailable(Clock::systemUTC),
                events.getIfAvailable());
    }

    /** Opt-in isolated Flyway delta for the binding tables (SQL54); mirrors the guarded V26-V29
     * precedents and never runs against the shared default datasource. */
    @Bean
    Object merchantStaffMemberMigration(javax.sql.DataSource source,
            @Value("${pet.merchant.staff-member.migration-enabled:false}") boolean enabled,
            @Value("${pet.merchant.staff-member.migration-database:}") String database)
            throws java.sql.SQLException {
        if (!enabled) return null;
        try (var connection = source.getConnection()) {
            if (database == null || !database.startsWith("staffb001_")
                    || !database.equals(connection.getCatalog())) {
                throw new IllegalStateException(
                        "Only explicit isolated staff-binding migration is permitted in this delivery");
            }
            org.flywaydb.core.Flyway.configure()
                    .dataSource(source)
                    .locations("classpath:db/staff-member-migration")
                    .table("staff_member_schema_history")
                    .load()
                    .migrate();
        }
        return null;
    }
}
