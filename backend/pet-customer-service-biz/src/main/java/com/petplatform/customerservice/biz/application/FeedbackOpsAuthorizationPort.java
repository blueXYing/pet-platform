package com.petplatform.customerservice.biz.application;

import java.time.OffsetDateTime;

/**
 * Boot/admin-biz adapter performs the approved strong current authorization read for the
 * contract-67 v0.2 feedback ops face (matrix row 18 complaint family; the assign code stays
 * unregistered — no dispatch in V1). Same shape as the contract-63/65 module-local ports;
 * customer-service biz never depends on another biz. Collection reads pass
 * {@link Port#checkCollection} before any row is scanned; single tickets pass
 * {@link Port#check} with the ticket scope facts. Fail-closed: a null/unavailable decision
 * never reads or writes.
 */
public final class FeedbackOpsAuthorizationPort {

    private FeedbackOpsAuthorizationPort() {}

    @FunctionalInterface
    public interface Port {
        Decision check(Check check);

        default Decision checkCollection(CollectionCheck check) {
            return null;
        }
    }

    /** Distinct type so the boot adapter implements this kernel port without FQN noise. */
    public interface FeedbackPort extends Port {}

    /** Controller-snapshot reference (never evidence on its own — the port re-proves it). */
    public record Authorization(String sessionId, long sessionGeneration) {}

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
