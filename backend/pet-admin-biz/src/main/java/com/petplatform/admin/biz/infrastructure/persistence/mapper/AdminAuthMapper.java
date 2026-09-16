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
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Admin auth statements (Schema 26). The SQL text is the pre-MyBatis code verbatim except that
 * JDBC {@code ?} placeholders are expressed as named {@code #{...}} bindings (PLAT-006).
 */
public interface AdminAuthMapper {

    @Update("SET SESSION time_zone='+00:00'")
    void setTimeZoneUtc();

    @Update("SET SESSION innodb_lock_wait_timeout=2")
    void setLockWaitTimeout2Seconds();

    @Select("SELECT UTC_TIMESTAMP(3) AS clock")
    Instant selectUtcClock();

    // --- admin_bootstrap ---

    @Select("SELECT * FROM admin_bootstrap WHERE id=1")
    Bootstrap selectBootstrap();

    @Select("SELECT * FROM admin_bootstrap WHERE id=1 FOR UPDATE")
    Bootstrap selectBootstrapForUpdate();

    @Update("UPDATE admin_bootstrap SET bootstrap_complete=TRUE,maintenance_mode=FALSE,version=version+1,completed_at=#{completedAt} WHERE id=1")
    int completeBootstrap(@Param("completedAt") Instant completedAt);

    @Update("UPDATE admin_bootstrap SET maintenance_mode=TRUE,version=version+1 WHERE id=1")
    int enableMaintenance();

    @Update("UPDATE admin_bootstrap SET maintenance_mode=FALSE,version=version+1 WHERE id=1")
    int disableMaintenance();

    // --- admin_auth_attempt ---

    @Select("SELECT * FROM admin_auth_attempt WHERE id=#{id}")
    AuthAttempt selectAttempt(@Param("id") long id);

    @Select("SELECT * FROM admin_auth_attempt WHERE id=#{id} FOR UPDATE")
    AuthAttempt selectAttemptForUpdate(@Param("id") long id);

    @Insert("""
            INSERT INTO admin_auth_attempt(id,secret_digest,binding_digest,expires_at,created_at,source_ip_digest)
            VALUES(#{id},#{secretDigest},#{bindingDigest},#{expiresAt},#{createdAt},#{sourceIpDigest})
            """)
    int insertAttempt(@Param("id") long id, @Param("secretDigest") byte[] secretDigest,
                      @Param("bindingDigest") byte[] bindingDigest, @Param("expiresAt") Instant expiresAt,
                      @Param("createdAt") Instant createdAt, @Param("sourceIpDigest") byte[] sourceIpDigest);

    @Update("UPDATE admin_auth_attempt SET risk_lookup_digest=#{riskLookupDigest} WHERE id=#{id}")
    int updateAttemptRisk(@Param("riskLookupDigest") byte[] riskLookupDigest, @Param("id") long id);

    @Update("UPDATE admin_auth_attempt SET status='COMPLETED',account_id=#{accountId},credential_version=#{credentialVersion},completed_result_id=#{completedResultId} WHERE id=#{id}")
    int completeAttempt(@Param("accountId") long accountId, @Param("credentialVersion") long credentialVersion,
                        @Param("completedResultId") long completedResultId, @Param("id") long id);

    // --- admin_audit_intent ---

    @Insert("""
            INSERT INTO admin_audit_intent(id,actor_id,actor_reference,attempt_id,action_code,resource_id,request_id,trace_ref,outcome,reason,occurred_at,next_attempt_at)
            VALUES(#{id},#{actorId},#{actorReference},#{attemptId},#{actionCode},#{resourceId},#{requestId},#{traceRef},#{outcome},#{reason},#{occurredAt},#{nextAttemptAt})
            """)
    int insertAuditIntent(@Param("id") long id, @Param("actorId") Long actorId,
                          @Param("actorReference") String actorReference, @Param("attemptId") Long attemptId,
                          @Param("actionCode") String actionCode, @Param("resourceId") Long resourceId,
                          @Param("requestId") byte[] requestId, @Param("traceRef") String traceRef,
                          @Param("outcome") String outcome, @Param("reason") String reason,
                          @Param("occurredAt") Instant occurredAt, @Param("nextAttemptAt") Instant nextAttemptAt);

    @Select("""
            SELECT id FROM admin_audit_intent WHERE delivery_state='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY id LIMIT #{limit}
            """)
    List<Long> selectPendingAuditIds(@Param("limit") int limit);

    @Select("SELECT * FROM admin_audit_intent WHERE id=#{id} FOR UPDATE")
    AuditIntent selectAuditIntentForUpdate(@Param("id") long id);

    @Update("UPDATE admin_audit_intent SET delivery_state='DELIVERED',delivered_at=UTC_TIMESTAMP(3),attempt_count=attempt_count+1 WHERE id=#{id}")
    int markAuditDelivered(@Param("id") long id);

    @Update("UPDATE admin_audit_intent SET attempt_count=attempt_count+1,next_attempt_at=TIMESTAMPADD(SECOND,5,UTC_TIMESTAMP(3)) WHERE id=#{id}")
    int deferAuditRetry(@Param("id") long id);

    // --- admin_auth_command ---

    @Select("SELECT * FROM admin_auth_command WHERE scope_key=#{scopeKey} AND namespace=#{namespace} AND request_id=#{requestId}")
    AuthCommand selectCommandByScope(@Param("scopeKey") byte[] scopeKey, @Param("namespace") String namespace,
                                     @Param("requestId") byte[] requestId);

    @Select("SELECT * FROM admin_auth_command WHERE scope_key=#{scopeKey} AND namespace=#{namespace} AND request_id=#{requestId} FOR UPDATE")
    AuthCommand selectCommandByScopeForUpdate(@Param("scopeKey") byte[] scopeKey, @Param("namespace") String namespace,
                                              @Param("requestId") byte[] requestId);

    @Select("SELECT * FROM admin_auth_command WHERE id=#{id}")
    AuthCommand selectCommand(@Param("id") long id);

    @Select("SELECT * FROM admin_auth_command WHERE id=#{id} FOR UPDATE")
    AuthCommand selectCommandForUpdate(@Param("id") long id);

    @Select("SELECT * FROM admin_auth_command WHERE attempt_id=#{attemptId} AND request_id=#{requestId} AND namespace='LOGIN'")
    AuthCommand selectCommandByAttemptAndRequest(@Param("attemptId") long attemptId,
                                                 @Param("requestId") byte[] requestId);

    @Insert("""
            INSERT INTO admin_auth_command(id,scope_key,attempt_id,namespace,request_id,parameter_mac,mac_key_id)
            VALUES(#{id},#{scopeKey},#{attemptId},#{namespace},#{requestId},#{parameterMac},#{macKeyId}) ON DUPLICATE KEY UPDATE id=id
            """)
    int insertCommandForAttempt(@Param("id") long id, @Param("scopeKey") byte[] scopeKey,
                                @Param("attemptId") long attemptId, @Param("namespace") String namespace,
                                @Param("requestId") byte[] requestId, @Param("parameterMac") byte[] parameterMac,
                                @Param("macKeyId") String macKeyId);

    @Insert("""
            INSERT INTO admin_auth_command(id,scope_key,session_id,namespace,request_id,parameter_mac,mac_key_id)
            VALUES(#{id},#{scopeKey},#{sessionId},#{namespace},#{requestId},#{parameterMac},#{macKeyId})
            """)
    int insertCommandForSession(@Param("id") long id, @Param("scopeKey") byte[] scopeKey,
                                @Param("sessionId") long sessionId, @Param("namespace") String namespace,
                                @Param("requestId") byte[] requestId, @Param("parameterMac") byte[] parameterMac,
                                @Param("macKeyId") String macKeyId);

    @Update("""
            UPDATE admin_auth_command SET state='SUCCEEDED',result_kind=#{resultKind},result_id=#{resultId},execution_ref=#{executionRef},cache_ref=#{cacheRef},completed_at=#{completedAt},receipt_window_anchor_at=#{receiptWindowAnchorAt},secret_expires_at=#{secretExpiresAt},result_expires_at=#{resultExpiresAt}
            WHERE id=#{id} AND state='RESERVED'
            """)
    int completeCommand(@Param("resultKind") String resultKind, @Param("resultId") long resultId,
                        @Param("executionRef") String executionRef, @Param("cacheRef") String cacheRef,
                        @Param("completedAt") Instant completedAt, @Param("receiptWindowAnchorAt") Instant receiptWindowAnchorAt,
                        @Param("secretExpiresAt") Instant secretExpiresAt, @Param("resultExpiresAt") Instant resultExpiresAt,
                        @Param("id") long id);

    @Update("UPDATE admin_auth_command SET failure_counted=TRUE WHERE id=#{id}")
    int markFailureCounted(@Param("id") long id);

    // --- admin_web_session ---

    @Select("SELECT * FROM admin_web_session WHERE id=#{id}")
    WebSession selectSession(@Param("id") long id);

    @Select("SELECT * FROM admin_web_session WHERE id=#{id} FOR UPDATE")
    WebSession selectSessionForUpdate(@Param("id") long id);

    @Select("SELECT * FROM admin_web_session WHERE token_digest=#{tokenDigest}")
    WebSession selectSessionByTokenDigest(@Param("tokenDigest") byte[] tokenDigest);

    @Insert("""
            INSERT INTO admin_web_session(id,account_id,token_digest,generation,issued_at,last_interactive_at,idle_expires_at)
            VALUES(#{id},#{accountId},#{tokenDigest},#{generation},#{issuedAt},#{lastInteractiveAt},#{idleExpiresAt})
            """)
    int insertSession(@Param("id") long id, @Param("accountId") long accountId,
                      @Param("tokenDigest") byte[] tokenDigest, @Param("generation") long generation,
                      @Param("issuedAt") Instant issuedAt, @Param("lastInteractiveAt") Instant lastInteractiveAt,
                      @Param("idleExpiresAt") Instant idleExpiresAt);

    @Update("UPDATE admin_web_session SET status='REVOKED',revoked_at=#{revokedAt} WHERE id=#{id}")
    int revokeSession(@Param("revokedAt") Instant revokedAt, @Param("id") long id);

    @Update("UPDATE admin_web_session SET last_interactive_at=#{lastInteractiveAt},idle_expires_at=#{idleExpiresAt} WHERE id=#{id}")
    int refreshSessionIdle(@Param("lastInteractiveAt") Instant lastInteractiveAt,
                           @Param("idleExpiresAt") Instant idleExpiresAt, @Param("id") long id);

    // --- admin_account ---

    @Select("SELECT * FROM admin_account WHERE id=#{id}")
    Account selectAccount(@Param("id") long id);

    @Select("SELECT * FROM admin_account WHERE id=#{id} FOR UPDATE")
    Account selectAccountForUpdate(@Param("id") long id);

    @Select("SELECT * FROM admin_account WHERE account_lookup=#{accountLookup}")
    Account selectAccountByLookup(@Param("accountLookup") byte[] accountLookup);

    @Select("SELECT COUNT(*) AS n FROM admin_account")
    long countAccounts();

    @Insert("""
            INSERT INTO admin_account(id,account_display,account_lookup,display_name,password_hash,created_at,updated_at)
            VALUES(#{id},#{accountDisplay},#{accountLookup},#{displayName},#{passwordHash},#{createdAt},#{updatedAt})
            """)
    int insertBootstrapAccount(@Param("id") long id, @Param("accountDisplay") String accountDisplay,
                               @Param("accountLookup") byte[] accountLookup, @Param("displayName") String displayName,
                               @Param("passwordHash") String passwordHash, @Param("createdAt") Instant createdAt,
                               @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE admin_account SET session_generation=#{sessionGeneration},last_login_at=#{lastLoginAt},updated_at=#{updatedAt} WHERE id=#{id}")
    int updateLoginSession(@Param("sessionGeneration") long sessionGeneration,
                           @Param("lastLoginAt") Instant lastLoginAt, @Param("updatedAt") Instant updatedAt,
                           @Param("id") long id);

    @Update("""
            UPDATE admin_account SET password_hash=#{passwordHash},credential_version=credential_version+1,session_generation=session_generation+1,status='ENABLED',version=version+1,updated_at=#{updatedAt}
            WHERE id=#{id}
            """)
    int updateRecoveredAccount(@Param("passwordHash") String passwordHash, @Param("updatedAt") Instant updatedAt,
                               @Param("id") long id);

    // --- admin_captcha ---

    @Select("SELECT * FROM admin_captcha WHERE id=#{id}")
    Captcha selectCaptcha(@Param("id") long id);

    @Select("SELECT * FROM admin_captcha WHERE attempt_id=#{attemptId} AND proof_digest=#{proofDigest} FOR UPDATE")
    Captcha selectCaptchaByProofForUpdate(@Param("attemptId") long attemptId,
                                          @Param("proofDigest") byte[] proofDigest);

    @Select("SELECT * FROM admin_captcha WHERE id=#{id} AND attempt_id=#{attemptId} FOR UPDATE")
    Captcha selectCaptchaForVerification(@Param("id") long id, @Param("attemptId") long attemptId);

    @Select("SELECT COUNT(*) AS n FROM admin_captcha WHERE attempt_id=#{attemptId}")
    long countCaptchasByAttempt(@Param("attemptId") long attemptId);

    @Insert("""
            INSERT INTO admin_captcha(id,attempt_id,answer_mac,mac_key_id,image_png,challenge_created_at,challenge_expires_at)
            VALUES(#{id},#{attemptId},#{answerMac},#{macKeyId},#{imagePng},#{challengeCreatedAt},#{challengeExpiresAt})
            """)
    int insertCaptcha(@Param("id") long id, @Param("attemptId") long attemptId,
                      @Param("answerMac") byte[] answerMac, @Param("macKeyId") String macKeyId,
                      @Param("imagePng") byte[] imagePng, @Param("challengeCreatedAt") Instant challengeCreatedAt,
                      @Param("challengeExpiresAt") Instant challengeExpiresAt);

    @Update("""
            UPDATE admin_captcha SET challenge_consumed_at=COALESCE(challenge_consumed_at,#{challengeConsumedAt}),proof_consumed_at=CASE WHEN proof_digest IS NOT NULL THEN COALESCE(proof_consumed_at,#{proofConsumedAt}) ELSE NULL END WHERE attempt_id=#{attemptId}
            """)
    int consumeStaleCaptchaChallenges(@Param("challengeConsumedAt") Instant challengeConsumedAt,
                                      @Param("proofConsumedAt") Instant proofConsumedAt,
                                      @Param("attemptId") long attemptId);

    @Update("UPDATE admin_captcha SET failure_count=failure_count+1 WHERE id=#{id}")
    int bumpCaptchaFailure(@Param("id") long id);

    @Update("UPDATE admin_captcha SET challenge_consumed_at=#{challengeConsumedAt},proof_digest=#{proofDigest},proof_issued_at=#{proofIssuedAt},proof_expires_at=#{proofExpiresAt} WHERE id=#{id}")
    int issueCaptchaProof(@Param("challengeConsumedAt") Instant challengeConsumedAt,
                          @Param("proofDigest") byte[] proofDigest, @Param("proofIssuedAt") Instant proofIssuedAt,
                          @Param("proofExpiresAt") Instant proofExpiresAt, @Param("id") long id);

    @Update("UPDATE admin_captcha SET proof_consumed_at=#{proofConsumedAt} WHERE id=#{id}")
    int markCaptchaProofConsumed(@Param("proofConsumedAt") Instant proofConsumedAt, @Param("id") long id);

    // --- admin_attempt_creation ---

    @Select("SELECT attempt_id FROM admin_attempt_creation WHERE namespace='ADMIN_LOGIN_CREATE' AND request_id=#{requestId}")
    Long selectAttemptCreation(@Param("requestId") byte[] requestId);

    @Insert("INSERT INTO admin_attempt_creation(namespace,request_id,attempt_id,created_at) VALUES('ADMIN_LOGIN_CREATE',#{requestId},#{attemptId},#{createdAt})")
    int insertAttemptCreation(@Param("requestId") byte[] requestId, @Param("attemptId") long attemptId,
                              @Param("createdAt") Instant createdAt);

    // --- admin_login_failure ---

    @Select("SELECT * FROM admin_login_failure WHERE lookup_digest=#{lookupDigest}")
    LoginFailure selectLoginFailure(@Param("lookupDigest") byte[] lookupDigest);

    @Select("SELECT * FROM admin_login_failure WHERE lookup_digest=#{lookupDigest} FOR UPDATE")
    LoginFailure selectLoginFailureForUpdate(@Param("lookupDigest") byte[] lookupDigest);

    @Insert("INSERT INTO admin_login_failure(lookup_digest,window_start,count,version) VALUES(#{lookupDigest},#{windowStart},0,0) ON DUPLICATE KEY UPDATE lookup_digest=lookup_digest")
    int insertLoginFailureSeed(@Param("lookupDigest") byte[] lookupDigest, @Param("windowStart") Instant windowStart);

    @Update("UPDATE admin_login_failure SET window_start=#{windowStart},count=#{count},version=version+1 WHERE lookup_digest=#{lookupDigest}")
    int updateLoginFailureWindow(@Param("windowStart") Instant windowStart, @Param("count") long count,
                                 @Param("lookupDigest") byte[] lookupDigest);

    @Update("UPDATE admin_login_failure SET window_start=#{windowStart},count=#{count},locked_until=#{lockedUntil},version=version+1 WHERE lookup_digest=#{lookupDigest}")
    int updateLoginFailureCount(@Param("windowStart") Instant windowStart, @Param("count") long count,
                                @Param("lockedUntil") Instant lockedUntil,
                                @Param("lookupDigest") byte[] lookupDigest);

    @Delete("DELETE FROM admin_login_failure WHERE lookup_digest=#{lookupDigest}")
    int deleteLoginFailure(@Param("lookupDigest") byte[] lookupDigest);

    // --- authorization snapshot ---

    @Select("SELECT * FROM admin_authz_revision WHERE id=1")
    AuthzRevision selectAuthzRevision();

    @Select("SELECT * FROM admin_authz_revision WHERE id=1 FOR UPDATE")
    AuthzRevision selectAuthzRevisionForUpdate();

    @Update("UPDATE admin_authz_revision SET revision=revision+1 WHERE id=1")
    int bumpAuthzRevision();

    @Select("SELECT * FROM admin_account_scope WHERE account_id=#{accountId}")
    AccountScope selectAccountScope(@Param("accountId") long accountId);

    @Select("SELECT city_code FROM admin_scope_city WHERE account_id=#{accountId}")
    List<String> selectScopeCities(@Param("accountId") long accountId);

    @Select("SELECT merchant_id FROM admin_scope_merchant WHERE account_id=#{accountId}")
    List<Long> selectScopeMerchants(@Param("accountId") long accountId);

    @Select("""
            SELECT r.* FROM admin_role r JOIN admin_account_role ar ON ar.role_id=r.id
            WHERE ar.account_id=#{accountId} AND r.status='ENABLED' ORDER BY r.role_code
            """)
    List<Role> selectEnabledRoles(@Param("accountId") long accountId);

    @Select("""
            SELECT ra.action_code FROM admin_role_action ra JOIN admin_role r ON r.id=ra.role_id
            JOIN admin_account_role ar ON ar.role_id=r.id WHERE ar.account_id=#{accountId} AND r.status='ENABLED'
            UNION SELECT action_code FROM admin_extra_grant WHERE account_id=#{accountId}
            """)
    List<String> selectActionGrants(@Param("accountId") long accountId);

    @Select("""
            SELECT COUNT(*) AS n FROM admin_account_role ar JOIN admin_role r ON r.id=ar.role_id
            WHERE ar.account_id=#{accountId} AND r.role_code='PLATFORM_SUPER_ADMIN' AND r.status='ENABLED'
            """)
    long countSuperAdminGrants(@Param("accountId") long accountId);

    // --- bootstrap data ---

    @Insert("INSERT INTO admin_role(id,role_code,display_name) VALUES(#{id},#{roleCode},#{displayName})")
    int insertRole(@Param("id") long id, @Param("roleCode") String roleCode,
                   @Param("displayName") String displayName);

    @Insert("INSERT INTO admin_account_role(account_id,role_id,granted_by,granted_at) VALUES(#{accountId},#{roleId},#{grantedBy},#{grantedAt})")
    int insertAccountRole(@Param("accountId") long accountId, @Param("roleId") long roleId,
                          @Param("grantedBy") long grantedBy, @Param("grantedAt") Instant grantedAt);

    @Insert("INSERT INTO admin_account_scope(account_id,mode) VALUES(#{accountId},'ALL')")
    int insertAccountScopeAll(@Param("accountId") long accountId);
}
