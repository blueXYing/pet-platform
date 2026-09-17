package com.petplatform.admin.biz.infrastructure.persistence.mapper;

import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.Account;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.AccountScope;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.AuthAttempt;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.AuthCommand;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.AuthzRevision;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.AuditIntent;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.Bootstrap;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.Captcha;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.LoginFailure;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.Role;
import com.petplatform.admin.biz.infrastructure.persistence.entity.AdminEntities.WebSession;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Admin auth access; SQL lives in resources/mapper/AdminAuthMapper.xml. */
public interface AdminAuthMapper {

    void setTimeZoneUtc();

    void setLockWaitTimeout2Seconds();

    Instant selectUtcClock();

    Bootstrap selectBootstrap();

    Bootstrap selectBootstrapForUpdate();

    int completeBootstrap(@Param("completedAt") Instant completedAt);

    int enableMaintenance();

    int disableMaintenance();

    AuthAttempt selectAttempt(@Param("id") long id);

    AuthAttempt selectAttemptForUpdate(@Param("id") long id);

    int insertAttempt(@Param("id") long id, @Param("secretDigest") byte[] secretDigest,
                      @Param("bindingDigest") byte[] bindingDigest, @Param("expiresAt") Instant expiresAt,
                      @Param("createdAt") Instant createdAt, @Param("sourceIpDigest") byte[] sourceIpDigest);

    int updateAttemptRisk(@Param("riskLookupDigest") byte[] riskLookupDigest, @Param("id") long id);

    int completeAttempt(@Param("accountId") long accountId, @Param("credentialVersion") long credentialVersion,
                        @Param("completedResultId") long completedResultId, @Param("id") long id);

    int insertAuditIntent(@Param("id") long id, @Param("actorId") Long actorId,
                          @Param("actorReference") String actorReference, @Param("attemptId") Long attemptId,
                          @Param("actionCode") String actionCode, @Param("resourceId") Long resourceId,
                          @Param("requestId") byte[] requestId, @Param("traceRef") String traceRef,
                          @Param("outcome") String outcome, @Param("reason") String reason,
                          @Param("occurredAt") Instant occurredAt, @Param("nextAttemptAt") Instant nextAttemptAt);

    List<Long> selectPendingAuditIds(@Param("limit") int limit);

    AuditIntent selectAuditIntentForUpdate(@Param("id") long id);

    int markAuditDelivered(@Param("id") long id);

    int deferAuditRetry(@Param("id") long id);

    AuthCommand selectCommandByScope(@Param("scopeKey") byte[] scopeKey, @Param("namespace") String namespace,
                                     @Param("requestId") byte[] requestId);

    AuthCommand selectCommandByScopeForUpdate(@Param("scopeKey") byte[] scopeKey, @Param("namespace") String namespace,
                                              @Param("requestId") byte[] requestId);

    AuthCommand selectCommand(@Param("id") long id);

    AuthCommand selectCommandForUpdate(@Param("id") long id);

    AuthCommand selectCommandByAttemptAndRequest(@Param("attemptId") long attemptId,
                                                 @Param("requestId") byte[] requestId);

    int insertCommandForAttempt(@Param("id") long id, @Param("scopeKey") byte[] scopeKey,
                                @Param("attemptId") long attemptId, @Param("namespace") String namespace,
                                @Param("requestId") byte[] requestId, @Param("parameterMac") byte[] parameterMac,
                                @Param("macKeyId") String macKeyId);

    int insertCommandForSession(@Param("id") long id, @Param("scopeKey") byte[] scopeKey,
                                @Param("sessionId") long sessionId, @Param("namespace") String namespace,
                                @Param("requestId") byte[] requestId, @Param("parameterMac") byte[] parameterMac,
                                @Param("macKeyId") String macKeyId);

    int completeCommand(@Param("resultKind") String resultKind, @Param("resultId") long resultId,
                        @Param("executionRef") String executionRef, @Param("cacheRef") String cacheRef,
                        @Param("completedAt") Instant completedAt, @Param("receiptWindowAnchorAt") Instant receiptWindowAnchorAt,
                        @Param("secretExpiresAt") Instant secretExpiresAt, @Param("resultExpiresAt") Instant resultExpiresAt,
                        @Param("id") long id);

    int markFailureCounted(@Param("id") long id);

    WebSession selectSession(@Param("id") long id);

    WebSession selectSessionForUpdate(@Param("id") long id);

    WebSession selectSessionByTokenDigest(@Param("tokenDigest") byte[] tokenDigest);

    int insertSession(@Param("id") long id, @Param("accountId") long accountId,
                      @Param("tokenDigest") byte[] tokenDigest, @Param("generation") long generation,
                      @Param("issuedAt") Instant issuedAt, @Param("lastInteractiveAt") Instant lastInteractiveAt,
                      @Param("idleExpiresAt") Instant idleExpiresAt);

    int revokeSession(@Param("revokedAt") Instant revokedAt, @Param("id") long id);

    int refreshSessionIdle(@Param("lastInteractiveAt") Instant lastInteractiveAt,
                           @Param("idleExpiresAt") Instant idleExpiresAt, @Param("id") long id);

    Account selectAccount(@Param("id") long id);

    Account selectAccountForUpdate(@Param("id") long id);

    Account selectAccountByLookup(@Param("accountLookup") byte[] accountLookup);

    long countAccounts();

    int insertBootstrapAccount(@Param("id") long id, @Param("accountDisplay") String accountDisplay,
                               @Param("accountLookup") byte[] accountLookup, @Param("displayName") String displayName,
                               @Param("passwordHash") String passwordHash, @Param("createdAt") Instant createdAt,
                               @Param("updatedAt") Instant updatedAt);

    int updateLoginSession(@Param("sessionGeneration") long sessionGeneration,
                           @Param("lastLoginAt") Instant lastLoginAt, @Param("updatedAt") Instant updatedAt,
                           @Param("id") long id);

    int updateRecoveredAccount(@Param("passwordHash") String passwordHash, @Param("updatedAt") Instant updatedAt,
                               @Param("id") long id);

    Captcha selectCaptcha(@Param("id") long id);

    Captcha selectCaptchaByProofForUpdate(@Param("attemptId") long attemptId,
                                          @Param("proofDigest") byte[] proofDigest);

    Captcha selectCaptchaForVerification(@Param("id") long id, @Param("attemptId") long attemptId);

    long countCaptchasByAttempt(@Param("attemptId") long attemptId);

    int insertCaptcha(@Param("id") long id, @Param("attemptId") long attemptId,
                      @Param("answerMac") byte[] answerMac, @Param("macKeyId") String macKeyId,
                      @Param("imagePng") byte[] imagePng, @Param("challengeCreatedAt") Instant challengeCreatedAt,
                      @Param("challengeExpiresAt") Instant challengeExpiresAt);

    int consumeStaleCaptchaChallenges(@Param("challengeConsumedAt") Instant challengeConsumedAt,
                                      @Param("proofConsumedAt") Instant proofConsumedAt,
                                      @Param("attemptId") long attemptId);

    int bumpCaptchaFailure(@Param("id") long id);

    int issueCaptchaProof(@Param("challengeConsumedAt") Instant challengeConsumedAt,
                          @Param("proofDigest") byte[] proofDigest, @Param("proofIssuedAt") Instant proofIssuedAt,
                          @Param("proofExpiresAt") Instant proofExpiresAt, @Param("id") long id);

    int markCaptchaProofConsumed(@Param("proofConsumedAt") Instant proofConsumedAt, @Param("id") long id);

    Long selectAttemptCreation(@Param("requestId") byte[] requestId);

    int insertAttemptCreation(@Param("requestId") byte[] requestId, @Param("attemptId") long attemptId,
                              @Param("createdAt") Instant createdAt);

    LoginFailure selectLoginFailure(@Param("lookupDigest") byte[] lookupDigest);

    LoginFailure selectLoginFailureForUpdate(@Param("lookupDigest") byte[] lookupDigest);

    int insertLoginFailureSeed(@Param("lookupDigest") byte[] lookupDigest, @Param("windowStart") Instant windowStart);

    int updateLoginFailureWindow(@Param("windowStart") Instant windowStart, @Param("count") long count,
                                 @Param("lookupDigest") byte[] lookupDigest);

    int updateLoginFailureCount(@Param("windowStart") Instant windowStart, @Param("count") long count,
                                @Param("lockedUntil") Instant lockedUntil,
                                @Param("lookupDigest") byte[] lookupDigest);

    int deleteLoginFailure(@Param("lookupDigest") byte[] lookupDigest);

    AuthzRevision selectAuthzRevision();

    AuthzRevision selectAuthzRevisionForUpdate();

    int bumpAuthzRevision();

    AccountScope selectAccountScope(@Param("accountId") long accountId);

    List<String> selectScopeCities(@Param("accountId") long accountId);

    List<Long> selectScopeMerchants(@Param("accountId") long accountId);

    List<Role> selectEnabledRoles(@Param("accountId") long accountId);

    List<String> selectActionGrants(@Param("accountId") long accountId);

    long countSuperAdminGrants(@Param("accountId") long accountId);

    int insertRole(@Param("id") long id, @Param("roleCode") String roleCode,
                   @Param("displayName") String displayName);

    int insertAccountRole(@Param("accountId") long accountId, @Param("roleId") long roleId,
                          @Param("grantedBy") long grantedBy, @Param("grantedAt") Instant grantedAt);

    int insertAccountScopeAll(@Param("accountId") long accountId);
}
