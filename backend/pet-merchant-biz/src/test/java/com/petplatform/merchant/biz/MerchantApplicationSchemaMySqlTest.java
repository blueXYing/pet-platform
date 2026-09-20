package com.petplatform.merchant.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Date;
import java.time.LocalDate;
import org.springframework.dao.DataAccessException;
import org.junit.jupiter.api.Test;

/** Contract constraints for SQL29, executed against an isolated real MySQL 8 database. */
class MerchantApplicationSchemaMySqlTest {

    private static final byte[] CREDIT_DIGEST = digest(1);
    private static final byte[] IDENTITY_DIGEST = digest(2);

    @Test
    void authoritativeSchemaLoadsOnRealMySql() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            assertEquals(11, db.jdbc().queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name LIKE 'merchant%'
                      AND table_name IN (
                        'merchant_subject_lookup_policy',
                        'merchant_application',
                        'merchant_application_revision',
                        'merchant_application_material',
                        'merchant_application_revision_material',
                        'merchant_credential_evidence',
                        'merchant_subject_claim',
                        'merchant_application_review_task',
                        'merchant_application_review_decision',
                        'merchant_application_audit',
                        'merchant_profile_compat'
                      )
                    """, Integer.class));
        }
    }

    @Test
    void revisionsCanReplaceSlotsButCannotAttachCrossApplicationMaterialsOrForgedEvidence()
            throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seedPolicy(db);
            seedDraft(db, 101, 1001, 2001, 301);
            seedDraft(db, 102, 1002, 2002, 302);
            seedRevision(db, 303, 101, 2);

            seedMaterial(db, 401, 101, 5001, "BUSINESS_LICENSE", "1".repeat(64));
            seedMaterial(db, 402, 101, 5002, "BUSINESS_LICENSE", "2".repeat(64));
            seedMaterial(db, 403, 102, 5003, "BUSINESS_LICENSE", "3".repeat(64));
            attach(db, 101, 301, 401, "BUSINESS_LICENSE", 1);
            attach(db, 101, 303, 402, "BUSINESS_LICENSE", 1);

            assertThrows(DataAccessException.class,
                    () -> attach(db, 101, 301, 403, "BUSINESS_LICENSE", 1));
            assertThrows(DataAccessException.class,
                    () -> attach(db, 101, 303, 401, "BUSINESS_LICENSE", 1));

            seedEvidence(db, 501, 101, 303, 402, "BUSINESS_LICENSE", "2".repeat(64),
                    "CREDIT_CODE", CREDIT_DIGEST);
            assertThrows(DataAccessException.class, () -> seedEvidence(
                    db, 502, 101, 303, 402, "BUSINESS_LICENSE", "f".repeat(64),
                    "CREDIT_CODE", CREDIT_DIGEST));
            assertThrows(DataAccessException.class, () -> seedEvidence(
                    db, 503, 101, 301, 402, "BUSINESS_LICENSE", "2".repeat(64),
                    "CREDIT_CODE", CREDIT_DIGEST));
            assertThrows(DataAccessException.class, () -> seedEvidence(
                    db, 504, 101, 303, 402, "BUSINESS_LICENSE", "2".repeat(64),
                    "IDENTITY_NUMBER", IDENTITY_DIGEST));
        }
    }

    @Test
    void activeSubjectClaimCannotBeDuplicatedOrDetachedFromVerifiedEvidence() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seedPolicy(db);
            seedDraft(db, 111, 1011, 2011, 311);
            seedDraft(db, 112, 1012, 2012, 312);
            seedMaterial(db, 411, 111, 5011, "BUSINESS_LICENSE", "a".repeat(64));
            seedMaterial(db, 412, 112, 5012, "BUSINESS_LICENSE", "b".repeat(64));
            attach(db, 111, 311, 411, "BUSINESS_LICENSE", 1);
            attach(db, 112, 312, 412, "BUSINESS_LICENSE", 1);
            seedEvidence(db, 511, 111, 311, 411, "BUSINESS_LICENSE", "a".repeat(64),
                    "CREDIT_CODE", CREDIT_DIGEST);
            seedEvidence(db, 512, 112, 312, 412, "BUSINESS_LICENSE", "b".repeat(64),
                    "CREDIT_CODE", CREDIT_DIGEST);
            seedClaim(db, 611, 111, 311, 511, "CREDIT_CODE", CREDIT_DIGEST);

            assertThrows(DataAccessException.class,
                    () -> seedClaim(db, 612, 112, 312, 512, "CREDIT_CODE", CREDIT_DIGEST));
            assertThrows(DataAccessException.class,
                    () -> seedClaim(db, 613, 111, 311, 511, "CREDIT_CODE", digest(9)));
            assertThrows(DataAccessException.class, () -> db.jdbc().update("""
                    UPDATE merchant_subject_lookup_policy SET key_version='v2' WHERE policy_slot=1
                    """));
        }
    }

    @Test
    void approvalRequiresSameRevisionClaimantDecisionAuditAndBothClaims() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seedPolicy(db);
            seedDraft(db, 121, 1021, 2021, 321);
            seedRevision(db, 322, 121, 2);
            seedMaterial(db, 421, 121, 5021, "BUSINESS_LICENSE", "c".repeat(64));
            seedMaterial(db, 422, 121, 5022, "ID_CARD_FRONT", "d".repeat(64));
            attach(db, 121, 321, 421, "BUSINESS_LICENSE", 1);
            attach(db, 121, 321, 422, "ID_CARD_FRONT", 1);
            attach(db, 121, 322, 421, "BUSINESS_LICENSE", 1);
            attach(db, 121, 322, 422, "ID_CARD_FRONT", 1);
            seedEvidence(db, 521, 121, 321, 421, "BUSINESS_LICENSE", "c".repeat(64),
                    "CREDIT_CODE", CREDIT_DIGEST);
            seedEvidence(db, 522, 121, 321, 422, "ID_CARD_FRONT", "d".repeat(64),
                    "IDENTITY_NUMBER", IDENTITY_DIGEST);
            seedEvidence(db, 523, 121, 322, 421, "BUSINESS_LICENSE", "c".repeat(64),
                    "CREDIT_CODE", CREDIT_DIGEST);
            seedEvidence(db, 524, 121, 322, 422, "ID_CARD_FRONT", "d".repeat(64),
                    "IDENTITY_NUMBER", IDENTITY_DIGEST);
            seedClaim(db, 621, 121, 321, 521, "CREDIT_CODE", CREDIT_DIGEST);
            seedClaim(db, 622, 121, 321, 522, "IDENTITY_NUMBER", IDENTITY_DIGEST);
            seedTask(db, 701, 121, 322);
            db.jdbc().update("""
                    UPDATE merchant_application_review_task
                    SET status='CLAIMED',claimed_by_operator_id=9001,claimed_at=NOW(3),version=1
                    WHERE id=701
                    """);
            db.jdbc().update("""
                    UPDATE merchant_application
                    SET application_no='SQ20260917ABCDEFGH',status='REVIEWING',
                        current_revision_id=322,submitted_revision_id=322,current_review_task_id=701,
                        subject_verification_status='VERIFIED',submitted_at=NOW(3),version=1
                    WHERE id=121
                    """);

            assertThrows(DataAccessException.class,
                    () -> seedDecision(db, 801, 121, 322, 701, 9002, 523, 524, 621, 622));
            assertThrows(DataAccessException.class,
                    () -> seedDecision(db, 802, 121, 321, 701, 9001, 521, 522, 621, 622));
            seedDecision(db, 803, 121, 322, 701, 9001, 523, 524, 621, 622);
            db.jdbc().update("""
                    UPDATE merchant_application_review_task
                    SET status='CLOSED',closed_at=NOW(3),version=2 WHERE id=701
                    """);
            assertThrows(DataAccessException.class,
                    () -> seedAudit(db, 901, 121, 321, 803, 9001));
            assertThrows(DataAccessException.class,
                    () -> seedAudit(db, 902, 121, 322, 803, 9002));
            seedAudit(db, 903, 121, 322, 803, 9001);

            assertThrows(DataAccessException.class, () -> db.jdbc().update("""
                    UPDATE merchant_application
                    SET status='APPROVED',
                        current_decision_id=803,current_decision_type='APPROVE',review_audit_id=903,
                        reviewed_at=NOW(3),version=2
                    WHERE id=121
                    """));
            assertThrows(DataAccessException.class, () -> db.jdbc().update("""
                    UPDATE merchant_application
                    SET status='APPROVED',
                        current_decision_id=803,current_decision_type='REJECT',review_audit_id=903,
                        current_credit_claim_id=621,current_credit_claim_type='CREDIT_CODE',
                        current_credit_claim_status='ACTIVE',
                        current_identity_claim_id=622,current_identity_claim_type='IDENTITY_NUMBER',
                        current_identity_claim_status='ACTIVE',reviewed_at=NOW(3),version=2
                    WHERE id=121
                    """));
            db.jdbc().update("""
                    UPDATE merchant_application
                    SET status='APPROVED',
                        current_decision_id=803,current_decision_type='APPROVE',review_audit_id=903,
                        current_credit_claim_id=621,current_credit_claim_type='CREDIT_CODE',
                        current_credit_claim_status='ACTIVE',
                        current_identity_claim_id=622,current_identity_claim_type='IDENTITY_NUMBER',
                        current_identity_claim_status='ACTIVE',
                        reviewed_at=NOW(3),version=2
                    WHERE id=121
                    """);
            assertEquals("APPROVED", db.jdbc().queryForObject(
                    "SELECT status FROM merchant_application WHERE id=121", String.class));
        }
    }

    @Test
    void applicationProfileCannotPointAtAnotherApplicationsReservedMerchant() throws Exception {
        try (var db = new MySqlMerchantApplicationSchemaTestDatabase()) {
            seedDraft(db, 131, 1031, 2031, 331);
            seedDraft(db, 132, 1032, 2032, 332);
            seedMerchant(db, 2031, 1031);
            seedMerchant(db, 2032, 1032);

            assertThrows(DataAccessException.class, () -> db.jdbc().update("""
                    INSERT INTO merchant_profile_compat
                    (merchant_id,application_id,source_revision_id,merchant_type_code,city_code,
                     source_kind,version,created_at,updated_at)
                    VALUES (2032,131,331,'PET_HOSPITAL','310100','APPLICATION',0,NOW(3),NOW(3))
                    """));
            db.jdbc().update("""
                    INSERT INTO merchant_profile_compat
                    (merchant_id,application_id,source_revision_id,merchant_type_code,city_code,
                     source_kind,version,created_at,updated_at)
                    VALUES (2031,131,331,'PET_HOSPITAL','310100','APPLICATION',0,NOW(3),NOW(3))
                    """);
        }
    }

    private static void seedPolicy(MySqlMerchantApplicationSchemaTestDatabase db) {
        db.jdbc().update("""
                INSERT INTO merchant_subject_lookup_policy(policy_slot,key_version,algorithm,created_at)
                VALUES (1,'v1','HMAC-SHA-256',NOW(3))
                """);
    }

    private static void seedDraft(MySqlMerchantApplicationSchemaTestDatabase db, long applicationId,
                                  long ownerId, long merchantId, long revisionId) {
        db.jdbc().update("""
                INSERT INTO merchant_application
                (id,owner_user_id,reserved_merchant_id,status,subject_verification_status,
                 version,created_at,updated_at)
                VALUES (?,?,?,'DRAFT','NOT_STARTED',0,NOW(3),NOW(3))
                """, applicationId, ownerId, merchantId);
        seedRevision(db, revisionId, applicationId, 1);
        db.jdbc().update("UPDATE merchant_application SET current_revision_id=? WHERE id=?",
                revisionId, applicationId);
    }

    private static void seedRevision(MySqlMerchantApplicationSchemaTestDatabase db, long revisionId,
                                     long applicationId, int revisionNo) {
        db.jdbc().update("""
                INSERT INTO merchant_application_revision
                (id,application_id,revision_no,created_by_user_id,created_at)
                VALUES (?,?,?,?,NOW(3))
                """, revisionId, applicationId, revisionNo, 10_000 + applicationId);
    }

    private static void seedMaterial(MySqlMerchantApplicationSchemaTestDatabase db, long materialId,
                                     long applicationId, long assetId, String type, String hash) {
        db.jdbc().update("""
                INSERT INTO merchant_application_material
                (id,application_id,material_type,private_asset_id,sha256,media_type,bytes,
                 uploaded_by_user_id,created_at)
                VALUES (?,?,?,?,?,'image/jpeg',1024,?,NOW(3))
                """, materialId, applicationId, type, assetId, hash, 10_000 + applicationId);
    }

    private static void attach(MySqlMerchantApplicationSchemaTestDatabase db, long applicationId,
                               long revisionId, long materialId, String type, int position) {
        db.jdbc().update("""
                INSERT INTO merchant_application_revision_material
                (application_id,revision_id,material_id,material_type,position)
                VALUES (?,?,?,?,?)
                """, applicationId, revisionId, materialId, type, position);
    }

    private static void seedEvidence(MySqlMerchantApplicationSchemaTestDatabase db, long evidenceId,
                                     long applicationId, long revisionId, long materialId,
                                     String materialType, String materialHash,
                                     String credentialType, byte[] digest) {
        db.jdbc().update("""
                INSERT INTO merchant_credential_evidence
                (id,application_id,revision_id,material_id,material_type,material_sha256,
                 evidence_source,evidence_status,credential_type,subject_name_protected,
                 identifier_protected,identifier_lookup_digest,lookup_policy_slot,
                 lookup_key_version,valid_from,valid_to,validity_kind,verified_by_operator_id,
                 verification_reason,observed_at)
                VALUES (?,?,?,?,?,?,'MANUAL','VERIFIED',?,?,?, ?,1,'v1',?,?,'DATED',9001,
                        '人工核验原件与主体一致',NOW(3))
                """, evidenceId, applicationId, revisionId, materialId, materialType, materialHash,
                credentialType, new byte[] {1}, new byte[] {2}, digest,
                Date.valueOf(LocalDate.of(2020, 1, 1)), Date.valueOf(LocalDate.of(2030, 1, 1)));
    }

    private static void seedClaim(MySqlMerchantApplicationSchemaTestDatabase db, long claimId,
                                  long applicationId, long revisionId, long evidenceId,
                                  String claimType, byte[] digest) {
        db.jdbc().update("""
                INSERT INTO merchant_subject_claim
                (id,claim_type,lookup_digest,lookup_policy_slot,lookup_key_version,
                 application_id,revision_id,evidence_id,evidence_status,status,claimed_at)
                VALUES (?,?,?,1,'v1',?,?,?,'VERIFIED','ACTIVE',NOW(3))
                """, claimId, claimType, digest, applicationId, revisionId, evidenceId);
    }

    private static void seedTask(MySqlMerchantApplicationSchemaTestDatabase db, long taskId,
                                 long applicationId, long revisionId) {
        db.jdbc().update("""
                INSERT INTO merchant_application_review_task
                (id,application_id,submitted_revision_id,submission_no,status,version,updated_at)
                VALUES (?,?,?,1,'AVAILABLE',0,NOW(3))
                """, taskId, applicationId, revisionId);
    }

    private static void seedDecision(MySqlMerchantApplicationSchemaTestDatabase db, long decisionId,
                                     long applicationId, long revisionId, long taskId, long operatorId,
                                     long creditEvidenceId, long identityEvidenceId,
                                     long creditClaimId, long identityClaimId) {
        db.jdbc().update("""
                INSERT INTO merchant_application_review_decision
                (id,application_id,submitted_revision_id,task_id,decision_type,
                 decided_by_operator_id,decided_at,authz_version,scope_version,request_id,
                 credit_evidence_id,credit_evidence_type,credit_evidence_status,
                 credit_evidence_digest,credit_evidence_policy_slot,credit_evidence_key_version,
                 identity_evidence_id,identity_evidence_type,identity_evidence_status,
                 identity_evidence_digest,identity_evidence_policy_slot,identity_evidence_key_version,
                 credit_claim_id,credit_claim_type,credit_claim_status,
                 identity_claim_id,identity_claim_type,identity_claim_status)
                VALUES (?,?,?,?,'APPROVE',?,NOW(3),'authz-1','scope-1',?,
                        ?,'CREDIT_CODE','VERIFIED',?,1,'v1',
                        ?,'IDENTITY_NUMBER','VERIFIED',?,1,'v1',
                        ?,'CREDIT_CODE','ACTIVE',?,'IDENTITY_NUMBER','ACTIVE')
                """, decisionId, applicationId, revisionId, taskId, operatorId,
                ("decision-" + decisionId).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                creditEvidenceId, CREDIT_DIGEST, identityEvidenceId, IDENTITY_DIGEST,
                creditClaimId, identityClaimId);
    }

    private static void seedAudit(MySqlMerchantApplicationSchemaTestDatabase db, long auditId,
                                  long applicationId, long revisionId, long decisionId,
                                  long operatorId) {
        db.jdbc().update("""
                INSERT INTO merchant_application_audit
                (id,application_id,revision_id,actor_type,actor_id,action_code,from_status,to_status,
                 request_id,occurred_at,decision_id)
                VALUES (?, ?,?,'PLATFORM_OPERATOR',?,'DECISION','REVIEWING','APPROVED',?,NOW(3),?)
                """, auditId, applicationId, revisionId, operatorId,
                ("audit-" + auditId).getBytes(java.nio.charset.StandardCharsets.UTF_8), decisionId);
    }

    private static void seedMerchant(MySqlMerchantApplicationSchemaTestDatabase db,
                                     long merchantId, long ownerId) {
        db.jdbc().update("""
                INSERT INTO merchant
                (id,owner_user_id,merchant_name,status,version,created_at,updated_at)
                VALUES (?,?,'schema qa merchant','ACTIVE',0,NOW(3),NOW(3))
                """, merchantId, ownerId);
    }

    private static byte[] digest(int lastByte) {
        byte[] digest = new byte[32];
        digest[31] = (byte) lastByte;
        return digest;
    }
}
