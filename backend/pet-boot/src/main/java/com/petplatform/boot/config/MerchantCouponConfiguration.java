package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.biz.apiimpl.MerchantCouponApiImpl;
import com.petplatform.coupon.biz.application.AdminCouponAuthorizationPort;
import com.petplatform.coupon.biz.event.MerchantCouponPaidConsumer;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.merchant.api.query.MerchantAdmissionQueryApi;
import com.petplatform.merchant.api.query.MerchantQueryApi;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Merchant self-built coupons (contract 79, CCR-MKT-MERCHANT-COUPON-001). Switched by
 * {@code pet.coupon.merchant.enabled}: bare application.yml keeps it off for isolated tests and
 * dev/test/prod turn it on (single definition per value, PR #234 five-layer config). Missing
 * owners fail closed at startup instead of opening a half-wired review face.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet", name = "coupon.merchant.enabled", havingValue = "true")
public class MerchantCouponConfiguration {

    @Bean
    MerchantCouponApiImpl merchantCouponApi(
            DataSource source, ObjectProvider<SnowflakeIdGenerator> ids,
            ObjectProvider<MerchantAdmissionQueryApi> admissions,
            ObjectProvider<AdminCouponAuthorizationPort.CouponPort> authorization,
            ObjectProvider<MerchantQueryApi> merchants, ObjectProvider<Clock> clock) {
        SnowflakeIdGenerator provider = ids.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("Merchant coupon kernel requires a Snowflake ID provider");
        }
        MerchantAdmissionQueryApi admission = admissions.getIfAvailable();
        if (admission == null) {
            throw new IllegalStateException("Merchant coupon kernel requires the merchant admission owner");
        }
        AdminCouponAuthorizationPort.CouponPort authz = authorization.getIfAvailable();
        if (authz == null) {
            throw new IllegalStateException(
                    "Merchant coupon kernel requires the admin authorization owner");
        }
        return new MerchantCouponApiImpl(source, provider, admission, authz,
                merchants.getIfAvailable(), clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * Budget ledger USE entry on payment success (79 section 6.3). The consume guard resolves
     * lazily so an outbox-less deployment fails at consume time, same as the flash-price consumer.
     */
    @Bean
    MerchantCouponPaidConsumer merchantCouponPaidConsumer(
            DataSource source, SnowflakeIdGenerator ids,
            ObjectProvider<JdbcOutboxConsumeGuard> guard, ObjectProvider<Clock> clock) {
        return new MerchantCouponPaidConsumer(
                source, ids,
                (name, event) -> {
                    JdbcOutboxConsumeGuard claims = guard.getIfAvailable();
                    if (claims == null) {
                        throw new IllegalStateException("outbox consume guard is unavailable");
                    }
                    return claims.tryClaim(name, event);
                },
                clock.getIfAvailable(Clock::systemUTC));
    }
}
