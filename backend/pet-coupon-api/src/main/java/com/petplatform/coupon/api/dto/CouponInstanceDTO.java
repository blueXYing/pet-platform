package com.petplatform.coupon.api.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * CCR-C006 P1 display projection of one coupon_instance. amountOff/thresholdAmount/scopeSummary/
 * typeLabel are server-side projections of coupon_template.rule_json whose structure is not
 * frozen yet (D1, pending CPN-001): each may be null and the client must never parse rule_json.
 */
public record CouponInstanceDTO(
        String couponId,
        String name,
        String amountOff,
        String thresholdAmount,
        String scopeSummary,
        String typeLabel,
        LocalDate validTo,
        String status,
        OffsetDateTime usedAt) {}
