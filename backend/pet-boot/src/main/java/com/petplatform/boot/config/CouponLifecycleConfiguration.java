package com.petplatform.boot.config;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.coupon.api.command.CouponCommandApi;
import com.petplatform.coupon.biz.apiimpl.CouponCommandApiImpl;
import com.petplatform.coupon.biz.event.RefundSucceededCouponConsumer;
import com.petplatform.event.core.JdbcOutboxConsumeGuard;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 61 coupon lifecycle (CPN-001/CPN-002), default ON per the 2026-10-16 ruling (doc 35). The command face joins the
 * caller's guarded ORDER transactions (38 create / 39 close / 41 payment result); the refund
 * restore consumer is dispatched by the outbox once the switch is on. With the switch off the
 * 38 kernel keeps its legacy fail-closed 503 for coupon-bearing creates and pending
 * RefundSucceededEvent rows simply wait (dispatched once enabled).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.coupon.lifecycle", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CouponLifecycleConfiguration {

    @Bean
    CouponCommandApi couponCommandApi(DataSource source, ScheduleCapacityGuardApi guard,
            SnowflakeIdGenerator ids) {
        return new CouponCommandApiImpl(source, guard, ids);
    }

    @Bean
    RefundSucceededCouponConsumer refundSucceededCouponConsumer(DataSource source,
            SnowflakeIdGenerator ids, JdbcOutboxConsumeGuard consumeGuard) {
        return new RefundSucceededCouponConsumer(source, ids, consumeGuard);
    }
}
