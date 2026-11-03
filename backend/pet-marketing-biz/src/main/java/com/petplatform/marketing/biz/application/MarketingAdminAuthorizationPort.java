package com.petplatform.marketing.biz.application;

import java.time.OffsetDateTime;

/** Module-local admin authorization port; boot adapts it to the admin owner. */
public interface MarketingAdminAuthorizationPort {

    record Check(
            String sessionId,
            long sessionGeneration,
            String operatorId,
            String actionCode,
            String resourceType,
            String resourceId,
            String cityCode,
            String merchantId) {}

    record Decision(boolean allowed, String authzVersion, OffsetDateTime checkedAt) {}

    Decision check(Check check);
}
