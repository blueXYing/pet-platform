package com.petplatform.points.biz.application;

import java.time.OffsetDateTime;

/**
 * Boot/admin-biz adapter performs the approved strong current authorization read for the
 * contract-65 platform points ledger face (same shape as the contract-63 trade ports; points
 * biz never depends on another biz). Collection reads pass {@link Port#checkCollection} before
 * any row is scanned. Fail-closed: a null/unavailable decision never reads.
 */
public final class AdminPointsAuthorizationPort {

    private AdminPointsAuthorizationPort() {}

    @FunctionalInterface
    public interface Port {
        Decision check(Check check);

        default Decision checkCollection(CollectionCheck check) {
            return null;
        }
    }

    /** Distinct type so boot adapters can implement each kernel port without FQN noise. */
    public interface PointsPort extends Port {}

    public record Check(
            String sessionId, long sessionGeneration, String operatorId, String actionCode,
            Resource resource, String purpose, Phase phase) {}

    public record CollectionCheck(
            String sessionId, long sessionGeneration, String operatorId, String actionCode,
            String purpose, Phase phase) {}

    public record Resource(
            String resourceType, String resourceId, String merchantId, String cityCode,
            String scopeVersion) {}

    public enum Phase {
        EXECUTE, READ_RESULT
    }

    public record Decision(
            boolean allowed, OffsetDateTime checkedAt, String authzVersion, String reasonCode) {}
}
