package com.petplatform.merchant.biz.application;

import java.time.OffsetDateTime;

/**
 * Boot adapter performs the approved strong current authorization read for the contract-63 v0.2
 * merchant disable action (same shape as the trade read face ports; merchant-biz never depends
 * on another biz). Fail-closed: a null/unavailable decision never writes.
 */
public final class AdminDispositionAuthorizationPort {

    private AdminDispositionAuthorizationPort() {}

    @FunctionalInterface
    public interface MerchantDispositionAuthorizationPort {
        Decision check(Check check);
    }

    public record Check(
            String sessionId,
            long sessionGeneration,
            String operatorId,
            String actionCode,
            String resourceType,
            String resourceId,
            String merchantId,
            String scopeVersion,
            String purpose) {}

    public record Decision(boolean allowed, OffsetDateTime checkedAt, String authzVersion, String reasonCode) {}
}
