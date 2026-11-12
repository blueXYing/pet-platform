package com.petplatform.boot.config;

import com.petplatform.merchant.api.query.MerchantOrderAuthorityApi;
import com.petplatform.merchant.api.query.MerchantStaffNamingApi;
import com.petplatform.merchant.biz.apiimpl.MerchantStaffNamingApiImpl;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import com.petplatform.verification.api.query.MerchantVerificationReadApi;
import com.petplatform.verification.biz.apiimpl.MerchantVerificationReadApiImpl;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * CCR-M-VERIFY-RECORD-001 (approved 2026-11-12) runtime: the merchant verification-record read
 * kernel plus its merchant-domain naming companion, behind
 * {@code pet.verification.merchant-read.http.enabled}, default off. The slice reuses the
 * contract-10 §4.1 order family's runtime chain — the schedule store guard, the OWNER
 * authority port and the MerchantOrderQueryApi batch card read — so the http switch additionally
 * requires {@code pet.order.merchant.enabled}; the controller beans inject the kernels
 * directly, so http=true with a kernel missing fails bean resolution at startup (same fail-fast
 * shape as the points-task and contract-63 slices). No biz depends on another biz: both
 * companions are api-layer ports adapted here.
 */
@Configuration(proxyBeanMethods = false)
public class MerchantVerificationReadConfiguration {

    /** Fail-fast composition: the read slice rides the merchant order runtime (guard/authority/cards). */
    @Bean
    Object merchantVerificationReadFlags(Environment e) {
        boolean http = e.getProperty("pet.verification.merchant-read.http.enabled", Boolean.class, false);
        boolean orderMerchant = e.getProperty("pet.order.merchant.enabled", Boolean.class, false);
        if (http && !orderMerchant) {
            throw new IllegalStateException(
                "Merchant verification read requires pet.order.merchant.enabled");
        }
        return new Object();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "pet.verification.merchant-read.http.enabled", havingValue = "true")
    static class Runtime {

        /** CCR §9 STAFF display names: read-only merchant_staff naming face (schema 52). */
        @Bean
        MerchantStaffNamingApi merchantStaffNamingApi(DataSource source) {
            return new MerchantStaffNamingApiImpl(source);
        }

        /** CCR §3 read kernel: OWNER re-proof + page statements over verification_record. */
        @Bean
        MerchantVerificationReadApi merchantVerificationReadApi(
            DataSource source, ScheduleCapacityGuardApi guard,
            MerchantOrderAuthorityApi authority, MerchantStaffNamingApi naming) {
            return new MerchantVerificationReadApiImpl(source, guard, authority, naming);
        }
    }
}
