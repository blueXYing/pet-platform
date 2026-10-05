package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantCurrentStaffFactsApi;
import com.petplatform.order.api.query.OrderProtectionFactsApi;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.schedule.api.protection.ScheduleProtectionFactsApi;
import com.petplatform.schedule.biz.apiimpl.ScheduleCapacityProofApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantCommandApiImpl;
import com.petplatform.schedule.biz.apiimpl.ScheduleMerchantQueryApiImpl;
import com.petplatform.schedule.biz.application.ScheduleAdmissionGate;
import com.petplatform.service.api.query.ServiceQueryApi;
import java.time.Clock;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SCH-004 merchant schedule write slice (PRD29, 34号 §2~§4, write-proposal v0.2): service
 * window / staff availability / capability maintenance commands plus the workbench reads,
 * default OFF via pet.schedule.command.enabled. Enabling additionally requires the reservation
 * protection foundation (pet.schedule.protection.enabled=true) whose shared beans provide the
 * per-store guard, the SCH facts, the MER staff facts and the ORDER assignment facts — the
 * write commands fail closed without them. Assembly stays on the schedule module's apiimpl /
 * application surface; persistence wiring is owned by schedule-biz (ARCH-002). The isolated
 * opt-in migration mirrors the guarded V27 service-write precedent: only an explicitly named
 * schw001_* database holding the SQL06 + SQL37 schedule tables is accepted, never the shared
 * default datasource.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.schedule.command", name = "enabled", havingValue = "true")
public class ScheduleWriteConfiguration {

    @Bean
    ScheduleAdmissionGate scheduleAdmissionGate(MerchantAdmissionQueryApi admissions) {
        return new ScheduleAdmissionGate(admissions);
    }

    @Bean
    ScheduleMerchantCommandApiImpl scheduleMerchantCommandApi(javax.sql.DataSource source,
            SnowflakeIdGenerator ids, ScheduleAdmissionGate admissions,
            ScheduleCapacityGuardApi guard, ScheduleProtectionFactsApi facts,
            MerchantCurrentStaffFactsApi merchant, OrderProtectionFactsApi orders,
            ServiceQueryApi serviceFacts, ScheduleCapacityProofApiImpl proof,
            org.springframework.beans.factory.ObjectProvider<Clock> clock) {
        return new ScheduleMerchantCommandApiImpl(source, ids, admissions, guard, facts, merchant,
                orders, serviceFacts, proof, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ScheduleMerchantQueryApiImpl scheduleMerchantQueryApi(javax.sql.DataSource source,
            ScheduleAdmissionGate admissions) {
        return new ScheduleMerchantQueryApiImpl(source, admissions);
    }

    /** Isolated opt-in Flyway migration for the schedule write delta (SQL53), mirroring the
     * guarded V26/V27 precedents; never runs against the shared default datasource. */
    @Bean
    Object scheduleWriteMigration(
            javax.sql.DataSource source,
            @Value("${pet.schedule.command.migration-enabled:false}") boolean enabled,
            @Value("${pet.schedule.command.migration-database:}") String database)
            throws java.sql.SQLException {
        if (!enabled) return null;
        try (var connection = source.getConnection()) {
            if (database == null || !database.startsWith("schw001_")
                    || !database.equals(connection.getCatalog())) {
                throw new IllegalStateException(
                        "Only explicit isolated schedule-write migration is permitted in this delivery");
            }
            Flyway.configure()
                    .dataSource(source)
                    .locations("classpath:db/schedule-migration")
                    .table("schedule_write_schema_history")
                    .load()
                    .migrate();
        }
        return null;
    }
}
