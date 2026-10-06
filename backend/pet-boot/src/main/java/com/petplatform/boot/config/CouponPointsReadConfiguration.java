package com.petplatform.boot.config;

import com.petplatform.coupon.biz.apiimpl.CouponQueryApiImpl;
import com.petplatform.points.biz.apiimpl.PointsQueryApiImpl;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * C-end coupon/points read slice (CCR-C006-COUPON-POINTS-READ-001 P1, approved 2026-1006):
 * assembled with the C-end session switch, read-only, no writers exist in this slice.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class CouponPointsReadConfiguration {

    @Bean
    CouponQueryApiImpl couponQueryApi(DataSource source) {
        return new CouponQueryApiImpl(source);
    }

    @Bean
    PointsQueryApiImpl pointsQueryApi(DataSource source) {
        return new PointsQueryApiImpl(source);
    }
}
