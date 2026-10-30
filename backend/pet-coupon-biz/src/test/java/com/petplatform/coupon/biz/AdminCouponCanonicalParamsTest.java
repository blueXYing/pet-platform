package com.petplatform.coupon.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.petplatform.coupon.biz.application.AdminCouponCanonicalParams;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminCouponCanonicalParamsTest {

    @Test
    void serializesCampaignTimesForTheIdempotencyBinding() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("startAt", OffsetDateTime.parse("2026-11-01T08:00:00Z"));
        params.put("endAt", OffsetDateTime.parse("2026-11-30T08:00:00Z"));
        var canonical = AdminCouponCanonicalParams.of(params);
        assertNotNull(canonical.sha256());
        assertEquals(64, canonical.sha256().length());
    }
}
