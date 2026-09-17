package com.petplatform.admin.biz.infrastructure.persistence.entity;

import java.time.Instant;

/**
 * Plain row holders for the admin-auth tables (Schema 26), public fields for MyBatis
 * auto-mapping. TIMESTAMP columns map through the MyBatis InstantTypeHandler with the
 * UTC calendar, matching the pre-migration UTC Timestamp reads/writes exactly.
 */
public final class AdminEntities {
    private AdminEntities() {}

    public static final class Bootstrap {
        public Boolean bootstrapComplete;
        public Boolean maintenanceMode;
    }

    public static final class AuthAttempt {
        public Long id;
        public byte[] secretDigest;
        public byte[] bindingDigest;
        public Instant expiresAt;
        public Instant createdAt;
        public byte[] sourceIpDigest;
        public byte[] riskLookupDigest;
        public String status;
        public Long accountId;
        public Long credentialVersion;
        public Long completedResultId;
    }

    public static final class AuthCommand {
        public Long id;
        public byte[] scopeKey;
        public Long attemptId;
        public Long sessionId;
        public String namespace;
        public byte[] requestId;
        public byte[] parameterMac;
        public String macKeyId;
        public String state;
        public Boolean failureCounted;
        public String resultKind;
        public Long resultId;
        public String executionRef;
        public String cacheRef;
        public Instant completedAt;
        public Instant receiptWindowAnchorAt;
        public Instant secretExpiresAt;
        public Instant resultExpiresAt;
    }

    public static final class WebSession {
        public Long id;
        public Long accountId;
        public byte[] tokenDigest;
        public Long generation;
        public Instant issuedAt;
        public Instant lastInteractiveAt;
        public Instant idleExpiresAt;
        public String status;
        public Instant revokedAt;
    }

    public static final class Account {
        public Long id;
        public String accountDisplay;
        public byte[] accountLookup;
        public String displayName;
        public String passwordHash;
        public String status;
        public Long credentialVersion;
        public Long sessionGeneration;
        public Instant lastLoginAt;
    }

    public static final class Captcha {
        public Long id;
        public Long attemptId;
        public byte[] answerMac;
        public String macKeyId;
        public byte[] imagePng;
        public Instant challengeCreatedAt;
        public Instant challengeExpiresAt;
        public Instant challengeConsumedAt;
        public byte[] proofDigest;
        public Instant proofIssuedAt;
        public Instant proofExpiresAt;
        public Instant proofConsumedAt;
        public Integer failureCount;
    }

    public static final class LoginFailure {
        public byte[] lookupDigest;
        public Instant windowStart;
        public Long count;
        public Long version;
        public Instant lockedUntil;
    }

    public static final class AuthzRevision {
        public String recoveryEpoch;
        public Long revision;
    }

    public static final class AccountScope {
        public Long accountId;
        public String mode;
    }

    public static final class Role {
        public Long id;
        public String roleCode;
        public String displayName;
        public String status;
    }

    public static final class AuditIntent {
        public Long id;
        public Long actorId;
        public String actorReference;
        public Long attemptId;
        public String actionCode;
        public Long resourceId;
        public byte[] requestId;
        public String traceRef;
        public String outcome;
        public String reason;
        public Instant occurredAt;
        public Instant nextAttemptAt;
        public String deliveryState;
        public Instant deliveredAt;
        public Long attemptCount;
    }
}
